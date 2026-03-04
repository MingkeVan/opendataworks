package com.onedata.portal.service.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.AqMetaLineageEdge;
import com.onedata.portal.entity.AqMetaTable;
import com.onedata.portal.mapper.AqMetaLineageEdgeMapper;
import com.onedata.portal.mapper.AqMetaTableMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final AqMetaTableMapper metaTableMapper;
    private final AqMetaLineageEdgeMapper lineageEdgeMapper;

    public List<MatchedTable> matchTables(Set<String> refs, String database) {
        if (CollectionUtils.isEmpty(refs)) {
            return Collections.emptyList();
        }

        Set<String> rawNames = refs.stream()
            .filter(StringUtils::hasText)
            .map(it -> it.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> tableNames = rawNames.stream()
            .map(this::stripDatabase)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        LambdaQueryWrapper<AqMetaTable> wrapper = new LambdaQueryWrapper<AqMetaTable>()
            .in(AqMetaTable::getTableName, tableNames)
            .orderByDesc(AqMetaTable::getUpdatedAt);

        if (StringUtils.hasText(database)) {
            wrapper.eq(AqMetaTable::getDbName, database.trim().toLowerCase(Locale.ROOT));
        }

        List<AqMetaTable> tables = metaTableMapper.selectList(wrapper);
        if (CollectionUtils.isEmpty(tables)) {
            tables = metaTableMapper.selectList(
                new LambdaQueryWrapper<AqMetaTable>()
                    .in(AqMetaTable::getTableName, tableNames)
                    .orderByDesc(AqMetaTable::getUpdatedAt)
            );
        }

        Map<String, AqMetaTable> bestByKey = new LinkedHashMap<String, AqMetaTable>();
        for (AqMetaTable table : tables) {
            String key = normalizedTableKey(table.getDbName(), table.getTableName());
            if (!bestByKey.containsKey(key)) {
                bestByKey.put(key, table);
            }
        }

        List<MatchedTable> matched = new ArrayList<MatchedTable>();
        for (String raw : rawNames) {
            String[] split = splitRef(raw);
            String db = split[0];
            String tableName = split[1];

            AqMetaTable hit = bestByKey.get(normalizedTableKey(db, tableName));
            if (hit == null && StringUtils.hasText(database)) {
                hit = bestByKey.get(normalizedTableKey(database.trim().toLowerCase(Locale.ROOT), tableName));
            }
            if (hit == null) {
                hit = tables.stream()
                    .filter(it -> tableName.equalsIgnoreCase(it.getTableName()))
                    .findFirst()
                    .orElse(null);
            }

            if (hit == null) {
                continue;
            }

            MatchedTable mt = new MatchedTable();
            mt.setRawRef(raw);
            mt.setTableId(hit.getTableId());
            mt.setDbName(hit.getDbName());
            mt.setTableName(hit.getTableName());
            mt.setTableComment(hit.getTableComment());
            matched.add(mt);
        }
        return matched;
    }

    public List<Map<String, Object>> buildLineageJoins(List<MatchedTable> matchedTables) {
        if (CollectionUtils.isEmpty(matchedTables) || matchedTables.size() < 2) {
            return Collections.emptyList();
        }

        Set<Long> tableIds = matchedTables.stream()
            .map(MatchedTable::getTableId)
            .filter(id -> id != null && id > 0)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (tableIds.size() < 2) {
            return Collections.emptyList();
        }

        List<AqMetaLineageEdge> edges = lineageEdgeMapper.selectList(
            new LambdaQueryWrapper<AqMetaLineageEdge>()
                .and(w -> w.in(AqMetaLineageEdge::getUpstreamTableId, tableIds)
                    .or()
                    .in(AqMetaLineageEdge::getDownstreamTableId, tableIds))
                .orderByDesc(AqMetaLineageEdge::getUpdatedAt)
        );
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptyList();
        }

        Map<Long, MatchedTable> tableMap = new HashMap<Long, MatchedTable>();
        for (MatchedTable mt : matchedTables) {
            tableMap.put(mt.getTableId(), mt);
        }

        List<Map<String, Object>> joins = new ArrayList<Map<String, Object>>();
        Set<String> edgeSet = new LinkedHashSet<String>();
        for (AqMetaLineageEdge edge : edges) {
            Long up = edge.getUpstreamTableId();
            Long down = edge.getDownstreamTableId();
            if (up == null || down == null) {
                continue;
            }
            MatchedTable upTable = tableMap.get(up);
            MatchedTable downTable = tableMap.get(down);
            if (upTable == null || downTable == null) {
                continue;
            }
            String edgeKey = up + "->" + down;
            if (!edgeSet.add(edgeKey)) {
                continue;
            }
            Map<String, Object> join = new LinkedHashMap<String, Object>();
            join.put("sourceTableId", up);
            join.put("sourceTable", qualify(upTable));
            join.put("targetTableId", down);
            join.put("targetTable", qualify(downTable));
            join.put("source", "aq_meta_lineage_edge");
            join.put("taskId", edge.getTaskId());
            join.put("confidence", 0.88D);
            joins.add(join);
        }
        return joins;
    }

    private String qualify(MatchedTable table) {
        if (!StringUtils.hasText(table.getDbName())) {
            return table.getTableName();
        }
        return table.getDbName() + "." + table.getTableName();
    }

    private String normalizedTableKey(String dbName, String tableName) {
        String db = StringUtils.hasText(dbName) ? dbName.trim().toLowerCase(Locale.ROOT) : "";
        String table = StringUtils.hasText(tableName) ? tableName.trim().toLowerCase(Locale.ROOT) : "";
        return db + "|" + table;
    }

    private String stripDatabase(String ref) {
        String[] split = splitRef(ref);
        return split[1];
    }

    private String[] splitRef(String ref) {
        String normalized = ref == null ? "" : ref.replace("`", "").trim().toLowerCase(Locale.ROOT);
        String[] split = normalized.split("\\.", 2);
        if (split.length == 2) {
            return split;
        }
        return new String[]{null, normalized};
    }

    @Data
    public static class MatchedTable {
        private String rawRef;
        private Long tableId;
        private String dbName;
        private String tableName;
        private String tableComment;
    }
}
