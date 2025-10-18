package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.SqlTableMatchResponse;
import com.onedata.portal.dto.SqlTableMatchResponse.MatchedTable;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 表名匹配服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlTableMatcherService {

    private static final Pattern UPSTREAM_PATTERN = Pattern.compile(
        "\\b(?:FROM|JOIN)\\s+(?:`?([a-z0-9_]+)`?\\.)?`?([a-z0-9_]+)`?",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DOWNSTREAM_PATTERN = Pattern.compile(
        "\\bINSERT\\s+INTO\\s+(?:`?([a-z0-9_]+)`?\\.)?`?([a-z0-9_]+)`?",
        Pattern.CASE_INSENSITIVE
    );

    private final DataTableMapper dataTableMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataLineageMapper dataLineageMapper;

    /**
     * 解析 SQL 返回匹配结果
     */
    public SqlTableMatchResponse match(String sql) {
        SqlTableMatchResponse response = new SqlTableMatchResponse();
        if (!StringUtils.hasText(sql)) {
            return response;
        }

        List<TableReference> upstreamRefs = extractTables(sql, UPSTREAM_PATTERN);
        List<TableReference> downstreamRefs = extractTables(sql, DOWNSTREAM_PATTERN);

        response.setUpstreamMatches(buildMatches(upstreamRefs, response.getUnmatchedUpstream()));
        response.setDownstreamMatches(buildMatches(downstreamRefs, response.getUnmatchedDownstream()));

        return response;
    }

    /**
     * 解析 SQL 并建立表与任务、血缘的关联
     */
    @Transactional
    public SqlTableMatchResponse bindTaskRelations(Long taskId, String sql) {
        if (taskId == null) {
            throw new IllegalArgumentException("任务ID不能为空");
        }

        SqlTableMatchResponse response = match(sql);

        // 清理旧数据
        tableTaskRelationMapper.delete(
            new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTaskId, taskId)
        );
        dataLineageMapper.delete(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getTaskId, taskId)
        );

        // 写入新的表任务关联
        for (MatchedTable matched : response.getUpstreamMatches()) {
            insertRelation(taskId, matched.getTable(), "read");
            insertLineage(taskId, matched.getTable().getId(), null, "input");
        }
        for (MatchedTable matched : response.getDownstreamMatches()) {
            insertRelation(taskId, matched.getTable(), "write");
            insertLineage(taskId, null, matched.getTable().getId(), "output");
        }

        // 上游下游组合生成表级血缘
        if (!CollectionUtils.isEmpty(response.getUpstreamMatches()) && !CollectionUtils.isEmpty(response.getDownstreamMatches())) {
            for (MatchedTable upstream : response.getUpstreamMatches()) {
                for (MatchedTable downstream : response.getDownstreamMatches()) {
                    insertLineage(taskId, upstream.getTable().getId(), downstream.getTable().getId(), "table");
                }
            }
        }

        return response;
    }

    private List<MatchedTable> buildMatches(List<TableReference> refs, List<String> unmatchedCollector) {
        List<MatchedTable> matches = new ArrayList<>();
        for (TableReference ref : refs) {
            DataTable table = matchTable(ref);
            if (table != null) {
                matches.add(new MatchedTable(ref.getRawName(), ref.getDatabase(), table));
            } else {
                unmatchedCollector.add(ref.getRawName());
            }
        }
        return matches;
    }

    private DataTable matchTable(TableReference ref) {
        if (ref == null || !StringUtils.hasText(ref.getTable())) {
            return null;
        }

        if (StringUtils.hasText(ref.getDatabase())) {
            DataTable exact = dataTableMapper.selectOne(
                new LambdaQueryWrapper<DataTable>()
                    .eq(DataTable::getDbName, ref.getDatabase())
                    .eq(DataTable::getTableName, ref.getTable())
            );
            if (exact != null) {
                return exact;
            }
        }

        DataTable byName = dataTableMapper.selectOne(
            new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getTableName, ref.getTable())
        );
        if (byName != null) {
            return byName;
        }

        List<DataTable> fuzzyList = dataTableMapper.selectList(
            new LambdaQueryWrapper<DataTable>()
                .like(DataTable::getTableName, ref.getTable())
        );
        if (!CollectionUtils.isEmpty(fuzzyList)) {
            return fuzzyList.get(0);
        }
        return null;
    }

    private List<TableReference> extractTables(String sql, Pattern pattern) {
        Matcher matcher = pattern.matcher(sql);
        Map<String, TableReference> refs = new LinkedHashMap<>();
        while (matcher.find()) {
            String database = normalizeIdentifier(matcher.group(1));
            String table = normalizeIdentifier(matcher.group(2));
            if (!StringUtils.hasText(table)) {
                continue;
            }
            String key = (database != null ? database + "." : "") + table;
            refs.computeIfAbsent(key, k -> new TableReference(database, table, k));
        }
        return new ArrayList<>(refs.values());
    }

    private String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.replace("`", "").trim().toLowerCase();
    }

    private void insertRelation(Long taskId, DataTable table, String relationType) {
        if (taskId == null || table == null) {
            return;
        }
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTaskId(taskId);
        relation.setTableId(table.getId());
        relation.setRelationType(relationType);
        tableTaskRelationMapper.insert(relation);
    }

    private void insertLineage(Long taskId, Long upstreamId, Long downstreamId, String lineageType) {
        DataLineage lineage = new DataLineage();
        lineage.setTaskId(taskId);
        lineage.setUpstreamTableId(upstreamId);
        lineage.setDownstreamTableId(downstreamId);
        lineage.setLineageType(lineageType);
        dataLineageMapper.insert(lineage);
    }

    private static class TableReference {
        private final String database;
        private final String table;
        private final String rawName;

        TableReference(String database, String table, String rawName) {
            this.database = database;
            this.table = table;
            this.rawName = rawName;
        }

        public String getDatabase() {
            return database;
        }

        public String getTable() {
            return table;
        }

        public String getRawName() {
            return rawName;
        }
    }
}
