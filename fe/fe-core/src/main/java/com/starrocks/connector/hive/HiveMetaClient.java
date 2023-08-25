// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.connector.hive;

import com.google.common.collect.Lists;
import com.starrocks.common.Config;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.hive.events.MetastoreNotificationFetchException;
import com.starrocks.sql.PlannerProfile;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaHookLoader;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.CurrentNotificationEventId;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.transport.TTransportException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.starrocks.connector.hive.HiveConnector.HIVE_METASTORE_URIS;

public class HiveMetaClient {
    private static final Logger LOG = LogManager.getLogger(HiveMetaClient.class);
    public static final String PARTITION_NULL_VALUE = "__HIVE_DEFAULT_PARTITION__";
    public static final String HUDI_PARTITION_NULL_VALUE = "default";
    private final HiveConf conf;

    // Required for creating an instance of RetryingMetaStoreClient.
    private static final HiveMetaHookLoader DUMMY_HOOK_LOADER = tbl -> null;

    public HiveMetaClient(HiveConf conf) {
        this.conf = conf;
    }

    public static HiveMetaClient createHiveMetaClient(Map<String, String> properties) {
        HiveConf conf = new HiveConf();
        properties.forEach(conf::set);
        if (properties.containsKey(HIVE_METASTORE_URIS)) {
            conf.set(MetastoreConf.ConfVars.THRIFT_URIS.getHiveName(), properties.get(HIVE_METASTORE_URIS));
        }
        conf.set(MetastoreConf.ConfVars.CLIENT_SOCKET_TIMEOUT.getHiveName(),
                String.valueOf(Config.hive_meta_store_timeout_s));
        return new HiveMetaClient(conf);
    }

    public int getClientSize() {
        return RecyclableClient.size();
    }

    private RecyclableClient getClient() throws MetaException {
        return RecyclableClient.getInstance(conf);
    }

    public <T> T callRPC(String methodName, String messageIfError, Object... args) {
        return callRPC(methodName, messageIfError, null, args);
    }

    public <T> T callRPC(String methodName, String messageIfError, Class<?>[] argClasses, Object... args) {
        RecyclableClient client = null;
        StarRocksConnectorException connectionException = null;

        try {
            client = getClient();
            argClasses = argClasses == null ? ClassUtils.getCompatibleParamClasses(args) : argClasses;
            Method method = client.getHiveClient().getClass().getDeclaredMethod(methodName, argClasses);
            if (client == null || client.getHiveClient() == null) {
                System.out.println("client is null or client is null");
            }
            return (T) method.invoke(client.getHiveClient(), args);
        } catch (Throwable e) {
            LOG.error(messageIfError, e);
            connectionException = new StarRocksConnectorException(messageIfError + ", msg: " + e.getMessage(), e);
            throw connectionException;
        } finally {
            if (client == null && connectionException != null) {
                LOG.error("Failed to get hive client. {}", connectionException.getMessage());
            } else if (connectionException != null) {
                LOG.error("An exception occurred when using the current long link " +
                        "to access metastore. msg: {}", messageIfError);
                client.close();
            } else if (client != null) {
                client.finish();
            }
        }
    }

    public List<String> getAllDatabaseNames() {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getAllDatabases")) {
            return callRPC("getAllDatabases", "Failed to getAllDatabases", new Object[0]);
        }
    }

    public void createDatabase(Database database) {
        Class<?>[] argClasses = {Database.class};

        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.createDatabase")) {
            callRPC("createDatabase", "Failed to create database " + database.getName(), argClasses, database);
        }
    }

    public void dropDatabase(String dbName, boolean deleteData) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.dropDatabase")) {
            callRPC("dropDatabase", "Failed to drop database " + dbName, dbName, deleteData, false, false);
        }
    }

    public List<String> getAllTableNames(String dbName) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getAllTables")) {
            return callRPC("getAllTables", "Failed to get all table names on database: " + dbName, dbName);
        }
    }

    public void createTable(Table table) {
        Class<?>[] argClasses = {Table.class};
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.createTable")) {
            callRPC("createTable", "Failed to create table " + table.getDbName() + "." + table.getTableName(),
                    argClasses, table);
        }
    }

    public void dropTable(String dbName, String tableName) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.dropTable")) {
            callRPC("dropTable", "Failed to drop table " + dbName + "." + tableName,
                    dbName, tableName, true, false);
        }
    }

    public void alterTable(String dbName, String tableName, Table newTable) {
        Class<?>[] argClasses = {String.class, String.class, Table.class};
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.alterTable")) {
            callRPC("alter_table", "Failed to alter table " + dbName + "." + tableName,
                    argClasses, dbName, tableName, newTable);
        }
    }

    public void alterPartition(String dbName, String tableName, Partition newPartition) {
        Class<?>[] argClasses = {String.class, String.class, Partition.class};
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.alterPartition")) {
            callRPC("alter_partition", "Failed to alter partition " + dbName + "." + tableName + newPartition.getValues(),
                    argClasses, dbName, tableName, newPartition);
        }
    }

    public List<String> getPartitionKeys(String dbName, String tableName) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.listPartitionNames")) {
            return callRPC("listPartitionNames", String.format("Failed to get partitionKeys on [%s.%s]", dbName, tableName),
                    dbName, tableName, (short) -1);
        }
    }

    public List<String> getPartitionKeysByValue(String dbName, String tableName, List<String> partitionValues) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.listPartitionNamesByValue")) {
            return callRPC("listPartitionNames", String.format("Failed to get partitionKeys on [%s.%s]", dbName, tableName),
                    dbName, tableName, partitionValues, (short) -1);
        }
    }

    public Database getDb(String dbName) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getDatabase")) {
            return callRPC("getDatabase", String.format("Failed to get database %s", dbName), dbName);
        }
    }

    public Table getTable(String dbName, String tableName) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getTable")) {
            return callRPC("getTable", String.format("Failed to get table [%s.%s]", dbName, tableName),
                    dbName, tableName);
        }
    }

    public Partition getPartition(String dbName, String tableName, List<String> partitionValues) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getPartition")) {
            return callRPC("getPartition", String.format("Failed to get partition on %s.%s", dbName, tableName),
                    dbName, tableName, partitionValues);
        }
    }

    public void addPartitions(String dbName, String tableName, List<Partition> partitions) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.addPartitions")) {
            callRPC("add_partitions", String.format("Failed to add partitions on %s.%s",
                    dbName, tableName), partitions);
        }
    }

    public void dropPartition(String dbName, String tableName, List<String> partValues, boolean deleteData) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.dropPartition")) {
            callRPC("dropPartition", String.format("Failed to drop partition on %s.%s.%s",
                    dbName, tableName, partValues), dbName, tableName, partValues, deleteData);
        }
    }

    /**
     * Both 'getPartitionsByNames' and 'getPartitionColumnStatistics' could throw exception or no response
     * when querying too many partitions at present. Due to statistics don't affect accuracy, user could adjust
     * session variable 'hive_partition_stats_sample_size' to ensure 'getPartitionColumnStat' normal return.
     * But "getPartitionsByNames" interface must return the full contents due to the need to get partition file information.
     * So we resend request "getPartitionsByNames" when an exception occurs.
     */
    public List<Partition> getPartitionsByNames(String dbName, String tblName, List<String> partitionNames) {
        int size = partitionNames.size();
        List<Partition> partitions;
        PlannerProfile.addCustomProperties("HMS.PARTITIONS.getPartitionsByNames." + tblName,
                String.format("%s partitions", size));

        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getPartitionsByNames")) {
            RecyclableClient client = null;
            StarRocksConnectorException connectionException = null;
            try {
                client = getClient();
                partitions = client.getHiveClient().getPartitionsByNames(dbName, tblName, partitionNames);
                if (partitions.size() != partitionNames.size()) {
                    LOG.warn("Expect to fetch {} partition on [{}.{}], but actually fetched {} partition",
                            partitionNames.size(), dbName, tblName, partitions.size());
                }
            } catch (TTransportException te) {
                partitions = getPartitionsWithRetry(dbName, tblName, partitionNames, 1);
            } catch (Exception e) {
                LOG.error("Failed to get partitions on {}.{}", dbName, tblName, e);
                connectionException = new StarRocksConnectorException("Failed to get partitions on [%s.%s] from meta store: %s",
                        dbName, tblName, e.getMessage());
                throw connectionException;
            } finally {
                if (client == null && connectionException != null) {
                    LOG.error("Failed to get hive client. {}", connectionException.getMessage());
                } else if (connectionException != null) {
                    LOG.error("An exception occurred when using the current long link " +
                            "to access metastore. msg: {}", connectionException.getMessage());
                    client.close();
                } else if (client != null) {
                    client.finish();
                }
            }
        }
        return partitions;
    }

    public List<ColumnStatisticsObj> getTableColumnStats(String dbName, String tableName, List<String> columns) {
        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getTableColumnStatistics")) {
            return callRPC("getTableColumnStatistics",
                    String.format("Failed to get table column statistics on [%s.%s]", dbName, tableName),
                    dbName, tableName, columns);
        }
    }

    public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStats(String dbName,
                                                                          String tableName,
                                                                          List<String> partitionNames,
                                                                          List<String> columnNames) {
        int size = partitionNames.size();
        PlannerProfile.addCustomProperties("HMS.PARTITIONS.getPartitionColumnStatistics." + tableName,
                String.format("%s partitions", size));

        try (PlannerProfile.ScopedTimer ignored = PlannerProfile.getScopedTimer("HMS.getPartitionColumnStatistics")) {
            return callRPC("getPartitionColumnStatistics",
                    String.format("Failed to get partitions column statistics on [%s.%s]. partition size: %d, columns size: %d.",
                            dbName, tableName, partitionNames.size(), columnNames.size()),
                    dbName, tableName, partitionNames, columnNames);
        }
    }

    /**
     * When the query scans many partitions in the table or the 'hive.metastore.try.direct.sql' in
     * hive metastore is false. The hive metastore will throw StackOverFlow exception.
     * We solve this problem by get partitions information multiple times.
     * Each retry reduces the number of partitions fetched by half until only one partition is fetched at a time.
     * @return Hive table partitions
     * @throws StarRocksConnectorException If there is an exception with only one partition at a time when get partition,
     * then we determine that there is a bug with the user's hive metastore.
     */
    private List<Partition> getPartitionsWithRetry(String dbName, String tableName,
                                                   List<String> partNames, int retryNum) throws StarRocksConnectorException {
        int subListSize = (int) Math.pow(2, retryNum);
        int subListNum = partNames.size() / subListSize;
        List<List<String>> partNamesList = Lists.partition(partNames, subListNum);
        List<Partition> partitions = Lists.newArrayList();

        LOG.warn("Execute getPartitionsByNames on [{}.{}] with {} times retry, slice size is {}, partName size is {}",
                dbName, tableName, retryNum, subListSize, partNames.size());

        RecyclableClient client = null;
        try {
            client = getClient();
            for (List<String> parts : partNamesList) {
                partitions.addAll(client.getHiveClient().getPartitionsByNames(dbName, tableName, parts));
            }
            LOG.info("Succeed to getPartitionByName on [{}.{}] with {} times retry, slice size is {}, partName size is {}",
                    dbName, tableName, retryNum, subListSize, partNames.size());
            return partitions;
        } catch (TTransportException te) {
            if (subListNum > 1) {
                return getPartitionsWithRetry(dbName, tableName, partNames, retryNum + 1);
            } else {
                throw new StarRocksConnectorException("Failed to getPartitionsByNames on [%s.%s] with slice size is %d",
                        dbName, tableName, subListNum);
            }
        } catch (Exception e) {
            throw new StarRocksConnectorException("Failed to getPartitionsNames on [%s.%s], msg: %s",
                    dbName, tableName, e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public CurrentNotificationEventId getCurrentNotificationEventId() {
        try {
            return callRPC("getCurrentNotificationEventId",
                    "Failed to fetch current notification event id", new Object[0]);
        } catch (Exception e) {
            throw new MetastoreNotificationFetchException(e.getMessage());
        }
    }

    public NotificationEventResponse getNextNotification(long lastEventId,
                                                         int maxEvents,
                                                         IMetaStoreClient.NotificationFilter filter)
            throws MetastoreNotificationFetchException {
        try {
            Class<?>[] argClasses = {long.class, int.class, IMetaStoreClient.NotificationFilter.class};
            return callRPC("getNextNotification", "Failed to get next notification based on last event id: " + lastEventId,
                    argClasses, lastEventId, maxEvents, filter);
        } catch (Exception e) {
            throw new MetastoreNotificationFetchException(e.getMessage());
        }
    }

    static class ClassUtils {
        private static final HashMap WRAPPER_TO_PRIMITIVE = new HashMap();
        static {
            WRAPPER_TO_PRIMITIVE.put(Boolean.class, Boolean.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Character.class, Character.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Byte.class, Byte.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Short.class, Short.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Integer.class, Integer.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Float.class, Float.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Long.class, Long.TYPE);
            WRAPPER_TO_PRIMITIVE.put(Double.class, Double.TYPE);
            WRAPPER_TO_PRIMITIVE.put(String.class, String.class);
        }

        public static Class<?>[] getCompatibleParamClasses(Object[] args) {
            Class<?>[] argTypes = new Class[args.length];
            for (int i = 0; i < args.length; i++) {
                argTypes[i] = toPrimitiveClass(args[i].getClass());
            }
            return argTypes;
        }

        public static Class<?> toPrimitiveClass(Class<?> parameterType) {
            if (List.class.isAssignableFrom(parameterType)) {
                return List.class;
            } else if (!parameterType.isPrimitive()) {
                Class<?> wrapperType = getWrapperType(parameterType);

                assert wrapperType != null;
                return wrapperType;
            } else {
                return parameterType;
            }
        }

        public static Class<?> getWrapperType(Class<?> primitiveType) {
            return (Class) WRAPPER_TO_PRIMITIVE.get(primitiveType);
        }
    }
}
