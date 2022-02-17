// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "exec/vectorized/hdfs_scanner.h"

#include <hdfs/hdfs.h>
#include <unistd.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <thread>

#include "env/env_hdfs.h"
#include "exec/vectorized/hdfs_scan_node.h"

namespace starrocks::vectorized {

Status HdfsScanner::init(RuntimeState* runtime_state, const HdfsScannerParams& scanner_params) {
    _runtime_state = runtime_state;
    _scanner_params = scanner_params;

    // which columsn do we need to scan.
    for (const auto& slot : _scanner_params.materialize_slots) {
        _scanner_columns.emplace_back(slot->col_name());
    }

    // general conjuncts.
    RETURN_IF_ERROR(Expr::clone_if_not_exists(_scanner_params.conjunct_ctxs, runtime_state, &_conjunct_ctxs));

    // slot id conjuncts.
    const auto& conjunct_ctxs_by_slot = _scanner_params.conjunct_ctxs_by_slot;
    if (!conjunct_ctxs_by_slot.empty()) {
        for (auto iter = conjunct_ctxs_by_slot.begin(); iter != conjunct_ctxs_by_slot.end(); ++iter) {
            SlotId slot_id = iter->first;
            _conjunct_ctxs_by_slot.insert({slot_id, std::vector<ExprContext*>()});
            RETURN_IF_ERROR(Expr::clone_if_not_exists(iter->second, runtime_state, &_conjunct_ctxs_by_slot[slot_id]));
        }
    }

    // min/max conjuncts.
    RETURN_IF_ERROR(
            Expr::clone_if_not_exists(_scanner_params.min_max_conjunct_ctxs, runtime_state, &_min_max_conjunct_ctxs));

    Status status = do_init(runtime_state, scanner_params);
    return status;
}

void HdfsScanner::_build_file_read_param() {
    HdfsFileReaderParam& param = _file_read_param;
    std::vector<ColumnPtr>& partition_values = param.partition_values;

    // evaluate partition values.
    for (size_t i = 0; i < _scanner_params.partition_slots.size(); i++) {
        int part_col_idx = _scanner_params._partition_index_in_hdfs_partition_columns[i];
        auto partition_value_column = _scanner_params.partition_values[part_col_idx]->evaluate(nullptr);
        DCHECK(partition_value_column->is_constant());
        partition_values.emplace_back(std::move(partition_value_column));
    }

    // build columns of materialized and partition.
    for (size_t i = 0; i < _scanner_params.materialize_slots.size(); i++) {
        auto* slot = _scanner_params.materialize_slots[i];

        HdfsFileReaderParam::ColumnInfo column;
        column.slot_desc = slot;
        column.col_idx = _scanner_params.materialize_index_in_chunk[i];
        column.col_type = slot->type();
        column.slot_id = slot->id();
        column.col_name = slot->col_name();

        param.materialized_columns.emplace_back(std::move(column));
    }

    for (size_t i = 0; i < _scanner_params.partition_slots.size(); i++) {
        auto* slot = _scanner_params.partition_slots[i];
        HdfsFileReaderParam::ColumnInfo column;
        column.slot_desc = slot;
        column.col_idx = _scanner_params.partition_index_in_chunk[i];
        column.col_type = slot->type();
        column.slot_id = slot->id();
        column.col_name = slot->col_name();

        param.partition_columns.emplace_back(std::move(column));
    }

    param.tuple_desc = _scanner_params.tuple_desc;
    param.conjunct_ctxs_by_slot = _conjunct_ctxs_by_slot;
    param.scan_ranges = _scanner_params.scan_ranges;
    param.min_max_conjunct_ctxs = _min_max_conjunct_ctxs;
    param.min_max_tuple_desc = _scanner_params.min_max_tuple_desc;
    param.timezone = _runtime_state->timezone();
    param.stats = &_stats;
}

Status HdfsScanner::get_next(RuntimeState* runtime_state, ChunkPtr* chunk) {
    RETURN_IF_CANCELLED(_runtime_state);
#ifndef BE_TEST
    SCOPED_TIMER(_scanner_params.parent->_scan_timer);
#endif
    Status status = do_get_next(runtime_state, chunk);
    if (status.ok()) {
        if (!_conjunct_ctxs.empty()) {
            SCOPED_RAW_TIMER(&_stats.expr_filter_ns);
            ExecNode::eval_conjuncts(_conjunct_ctxs, (*chunk).get());
        }
    } else if (status.is_end_of_file()) {
        // do nothing.
    } else {
        LOG(ERROR) << "failed to read file: " << _scanner_params.fs->file_name();
    }
    return status;
}

Status HdfsScanner::open(RuntimeState* runtime_state) {
    if (_is_open) {
        return Status::OK();
    }
#ifndef BE_TEST
    if (_scanner_params.fs_handle_type == HdfsFsHandle::Type::HDFS) {
        auto* file = down_cast<HdfsRandomAccessFile*>(_scanner_params.fs.get());
        RETURN_IF_ERROR(file->open());
    }
#endif
    _build_file_read_param();
    auto status = do_open(runtime_state);
    if (status.ok()) {
        _is_open = true;
#ifndef BE_TEST
        (*_scanner_params.open_limit)++;
#endif
        LOG(INFO) << "open file success: " << _scanner_params.fs->file_name();
    }
    return status;
}

void HdfsScanner::close(RuntimeState* runtime_state) noexcept {
    DCHECK(!has_pending_token());
    if (_is_closed) {
        return;
    }
    Expr::close(_conjunct_ctxs, runtime_state);
    Expr::close(_min_max_conjunct_ctxs, runtime_state);
    for (auto& it : _conjunct_ctxs_by_slot) {
        Expr::close(it.second, runtime_state);
    }
    do_close(runtime_state);
#ifndef BE_TEST
    if (_scanner_params.fs_handle_type == HdfsFsHandle::Type::HDFS) {
        auto* file = down_cast<HdfsRandomAccessFile*>(_scanner_params.fs.get());
        file->close();
    }
    if (_is_open) {
        (*_scanner_params.open_limit)--;
    }
#endif
    _scanner_params.fs.reset();
    _is_closed = true;
}

void HdfsScanner::enter_pending_queue() {
    _pending_queue_sw.start();
}

uint64_t HdfsScanner::exit_pending_queue() {
    return _pending_queue_sw.reset();
}

void HdfsScanner::update_counter() {
    static const char* const kHdfsIOProfileSectionPrefix = "HdfsIO";
    if (_scanner_params.fs == nullptr) return;

    auto res = _scanner_params.fs->get_numeric_statistics();
    if (!res.ok()) return;

    std::unique_ptr<NumericStatistics> statistics = std::move(res).value();
    if (statistics == nullptr || statistics->size() == 0) return;

    auto&& profile = _scanner_params.parent->runtime_profile();
    [[maybe_unused]] auto&& toplev = ADD_TIMER(profile, kHdfsIOProfileSectionPrefix);
    for (int64_t i = 0, sz = statistics->size(); i < sz; i++) {
        auto&& name = statistics->name(i);
        auto&& counter = ADD_CHILD_COUNTER(profile, name, TUnit::UNIT, kHdfsIOProfileSectionPrefix);
        COUNTER_UPDATE(counter, statistics->value(i));
    }
}

void HdfsFileReaderParam::set_columns_from_file(const std::unordered_set<std::string>& names) {
    for (auto& column : materialized_columns) {
        if (names.find(column.col_name) == names.end()) {
            not_existed_slots.push_back(column.slot_desc);
            SlotId slot_id = column.slot_id;
            if (conjunct_ctxs_by_slot.find(slot_id) != conjunct_ctxs_by_slot.end()) {
                for (ExprContext* ctx : conjunct_ctxs_by_slot[slot_id]) {
                    conjunct_ctxs_of_non_existed_slots.emplace_back(ctx);
                }
                conjunct_ctxs_by_slot.erase(slot_id);
            }
        }
    }
}

void HdfsFileReaderParam::append_not_exised_columns_to_chunk(vectorized::ChunkPtr* chunk, size_t row_count) {
    if (not_existed_slots.size() == 0) return;

    ChunkPtr& ck = (*chunk);
    ck->set_num_rows(row_count);
    for (auto* slot_desc : not_existed_slots) {
        auto col = ColumnHelper::create_column(slot_desc->type(), slot_desc->is_nullable());
        if (row_count > 0) {
            col->append_default(row_count);
        }
        ck->append_column(std::move(col), slot_desc->id());
    }
}

bool HdfsFileReaderParam::should_skip_by_evaluating_not_existed_slots() {
    if (not_existed_slots.size() == 0) return false;

    // build chunk for evaluation.
    ChunkPtr chunk = std::make_shared<Chunk>();
    append_not_exised_columns_to_chunk(&chunk, 1);
    // do evaluation.
    {
        SCOPED_RAW_TIMER(&stats->expr_filter_ns);
        ExecNode::eval_conjuncts(conjunct_ctxs_of_non_existed_slots, chunk.get());
    }
    return !(chunk->has_rows());
}

void HdfsFileReaderParam::append_partition_column_to_chunk(vectorized::ChunkPtr* chunk, size_t row_count) {
    if (partition_columns.size() == 0) return;

    ChunkPtr& ck = (*chunk);
    ck->set_num_rows(row_count);

    for (size_t i = 0; i < partition_columns.size(); i++) {
        SlotDescriptor* slot_desc = partition_columns[i].slot_desc;
        DCHECK(partition_values[i]->is_constant());
        auto* const_column = vectorized::ColumnHelper::as_raw_column<vectorized::ConstColumn>(partition_values[i]);
        ColumnPtr data_column = const_column->data_column();
        auto chunk_part_column = ColumnHelper::create_column(slot_desc->type(), slot_desc->is_nullable());

        if (row_count > 0) {
            if (data_column->is_nullable()) {
                chunk_part_column->append_nulls(1);
            } else {
                chunk_part_column->append(*data_column, 0, 1);
            }
            chunk_part_column->assign(row_count, 0);
        }
        ck->append_column(std::move(chunk_part_column), slot_desc->id());
    }
}

bool HdfsFileReaderParam::can_use_dict_filter_on_slot(SlotDescriptor* slot) const {
    if (!slot->type().is_string_type()) {
        return false;
    }
    SlotId slot_id = slot->id();
    if (conjunct_ctxs_by_slot.find(slot_id) == conjunct_ctxs_by_slot.end()) {
        return false;
    }
    for (ExprContext* ctx : conjunct_ctxs_by_slot.at(slot_id)) {
        const Expr* root_expr = ctx->root();
        if (root_expr->node_type() == TExprNodeType::FUNCTION_CALL) {
            std::string is_null_str;
            if (root_expr->is_null_scalar_function(is_null_str)) {
                return false;
            }
        }
    }
    return true;
}

} // namespace starrocks::vectorized
