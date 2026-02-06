package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 血缘关系服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageService {

    private final DataLineageMapper dataLineageMapper;
    private final DataTableMapper dataTableMapper;

    /**
     * 获取血缘图数据
     */
    public LineageGraph getLineageGraph() {
        return getLineageGraph(null, null, null, null, null, null, null, null);
    }

    /**
     * 获取血缘图数据（支持筛选）
     */
    public LineageGraph getLineageGraph(String layer, String businessDomain, String dataDomain, String keyword) {
        return getLineageGraph(layer, businessDomain, dataDomain, keyword, null, null, null, null);
    }

    /**
     * 获取血缘图数据（支持数据源/schema/中心表/层级筛选）
     */
    public LineageGraph getLineageGraph(
            String layer,
            String businessDomain,
            String dataDomain,
            String keyword,
            Long clusterId,
            String dbName,
            Long centerTableId,
            Integer depth) {
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(layer)) {
            tableWrapper.eq(DataTable::getLayer, layer.toUpperCase());
        }
        if (StringUtils.hasText(businessDomain)) {
            tableWrapper.eq(DataTable::getBusinessDomain, businessDomain);
        }
        if (StringUtils.hasText(dataDomain)) {
            tableWrapper.eq(DataTable::getDataDomain, dataDomain);
        }
        if (clusterId != null) {
            tableWrapper.eq(DataTable::getClusterId, clusterId);
        }
        if (StringUtils.hasText(dbName)) {
            tableWrapper.eq(DataTable::getDbName, dbName.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String trimmedKeyword = keyword.trim();
            tableWrapper.and(w -> w.like(DataTable::getTableName, trimmedKeyword)
                .or().like(DataTable::getTableComment, trimmedKeyword));
        }
        tableWrapper.ne(DataTable::getStatus, "deprecated");

        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);
        if (tables.isEmpty()) {
            return emptyGraph();
        }

        Map<Long, DataTable> tableMap = tables.stream()
            .collect(Collectors.toMap(DataTable::getId, t -> t));

        if (centerTableId != null && !tableMap.containsKey(centerTableId)) {
            return emptyGraph();
        }

        Set<Long> tableIds = new HashSet<>(tableMap.keySet());

        List<DataLineage> lineages = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .and(wrapper -> wrapper.in(DataLineage::getUpstreamTableId, tableIds)
                    .or()
                    .in(DataLineage::getDownstreamTableId, tableIds))
        );

        List<EdgeRecord> allEdges = buildEdges(lineages, tableIds);
        Set<Long> visibleTableIds = centerTableId == null
                ? new LinkedHashSet<>(tableIds)
                : collectReachableTableIds(centerTableId, allEdges, depth);

        List<LineageNode> nodes = visibleTableIds.stream()
                .map(tableMap::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(table -> String.valueOf(table.getTableName())))
                .map(this::buildLineageNode)
                .collect(Collectors.toList());

        List<LineageEdge> edges = allEdges.stream()
                .filter(edge -> visibleTableIds.contains(edge.sourceTableId) && visibleTableIds.contains(edge.targetTableId))
                .map(edge -> {
                    LineageEdge lineageEdge = new LineageEdge();
                    lineageEdge.setSource(String.valueOf(edge.sourceTableId));
                    lineageEdge.setTarget(String.valueOf(edge.targetTableId));
                    lineageEdge.setTaskId(edge.taskId);
                    return lineageEdge;
                })
                .collect(Collectors.toList());

        if (centerTableId != null && nodes.stream().noneMatch(node -> String.valueOf(centerTableId).equals(node.getId()))) {
            DataTable centerTable = tableMap.get(centerTableId);
            if (centerTable != null) {
                nodes.add(buildLineageNode(centerTable));
            }
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private List<EdgeRecord> buildEdges(List<DataLineage> lineages, Set<Long> validTableIds) {
        if (lineages == null || lineages.isEmpty() || validTableIds == null || validTableIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<DataLineage>> lineagesByTask = lineages.stream()
                .filter(lineage -> lineage.getTaskId() != null)
                .collect(Collectors.groupingBy(DataLineage::getTaskId));

        List<EdgeRecord> edges = new ArrayList<>();
        for (Map.Entry<Long, List<DataLineage>> entry : lineagesByTask.entrySet()) {
            Long taskId = entry.getKey();
            List<DataLineage> taskLineages = entry.getValue();
            Set<String> uniquePairs = new HashSet<>();

            // 兼容直接表级血缘（lineage_type=table）或已有上下游都写入同一行的数据
            for (DataLineage lineage : taskLineages) {
                Long upstreamId = lineage.getUpstreamTableId();
                Long downstreamId = lineage.getDownstreamTableId();
                if (upstreamId == null || downstreamId == null) {
                    continue;
                }
                if (!validTableIds.contains(upstreamId) || !validTableIds.contains(downstreamId)) {
                    continue;
                }
                String key = upstreamId + "->" + downstreamId;
                if (!uniquePairs.add(key)) {
                    continue;
                }
                edges.add(new EdgeRecord(upstreamId, downstreamId, taskId));
            }

            Set<Long> inputTableIds = taskLineages.stream()
                    .filter(l -> "input".equals(l.getLineageType()) && l.getUpstreamTableId() != null)
                    .map(DataLineage::getUpstreamTableId)
                    .filter(validTableIds::contains)
                    .collect(Collectors.toSet());

            Set<Long> outputTableIds = taskLineages.stream()
                    .filter(l -> "output".equals(l.getLineageType()) && l.getDownstreamTableId() != null)
                    .map(DataLineage::getDownstreamTableId)
                    .filter(validTableIds::contains)
                    .collect(Collectors.toSet());

            for (Long upstreamId : inputTableIds) {
                for (Long downstreamId : outputTableIds) {
                    String key = upstreamId + "->" + downstreamId;
                    if (!uniquePairs.add(key)) {
                        continue;
                    }
                    edges.add(new EdgeRecord(upstreamId, downstreamId, taskId));
                }
            }
        }
        return edges;
    }

    private Set<Long> collectReachableTableIds(Long centerTableId, List<EdgeRecord> edges, Integer depth) {
        if (centerTableId == null) {
            return Collections.emptySet();
        }
        int maxDepth = normalizeDepth(depth);

        Map<Long, Set<Long>> outgoing = new HashMap<>();
        Map<Long, Set<Long>> incoming = new HashMap<>();
        for (EdgeRecord edge : edges) {
            outgoing.computeIfAbsent(edge.sourceTableId, key -> new LinkedHashSet<>()).add(edge.targetTableId);
            incoming.computeIfAbsent(edge.targetTableId, key -> new LinkedHashSet<>()).add(edge.sourceTableId);
        }

        Set<Long> upstreamReachable = traverseDirectional(centerTableId, incoming, maxDepth);
        Set<Long> downstreamReachable = traverseDirectional(centerTableId, outgoing, maxDepth);

        Set<Long> result = new LinkedHashSet<>();
        result.addAll(upstreamReachable);
        result.addAll(downstreamReachable);
        return result;
    }

    private Set<Long> traverseDirectional(Long centerTableId, Map<Long, Set<Long>> adjacency, int maxDepth) {
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(centerTableId);
        Set<Long> frontier = new LinkedHashSet<>();
        frontier.add(centerTableId);

        int currentDepth = 0;
        while (!frontier.isEmpty() && (maxDepth < 0 || currentDepth < maxDepth)) {
            Set<Long> nextFrontier = new LinkedHashSet<>();
            for (Long current : frontier) {
                nextFrontier.addAll(adjacency.getOrDefault(current, Collections.emptySet()));
            }
            nextFrontier.removeAll(visited);
            if (nextFrontier.isEmpty()) {
                break;
            }
            visited.addAll(nextFrontier);
            frontier = nextFrontier;
            currentDepth++;
        }
        return visited;
    }

    private int normalizeDepth(Integer depth) {
        if (depth == null) {
            return 1;
        }
        if (depth <= 0) {
            return -1;
        }
        return depth;
    }

    private LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(Collections.emptyList());
        graph.setEdges(Collections.emptyList());
        return graph;
    }

    private LineageNode buildLineageNode(DataTable table) {
        LineageNode node = new LineageNode();
        node.setId(String.valueOf(table.getId()));
        node.setTableId(table.getId());
        node.setName(table.getTableName());
        node.setLayer(table.getLayer());
        node.setComment(table.getTableComment());
        node.setBusinessDomain(table.getBusinessDomain());
        node.setDataDomain(table.getDataDomain());
        node.setClusterId(table.getClusterId());
        node.setDbName(table.getDbName());
        return node;
    }

    private static class EdgeRecord {
        private final Long sourceTableId;
        private final Long targetTableId;
        private final Long taskId;

        private EdgeRecord(Long sourceTableId, Long targetTableId, Long taskId) {
            this.sourceTableId = sourceTableId;
            this.targetTableId = targetTableId;
            this.taskId = taskId;
        }
    }

    @Data
    public static class LineageGraph {
        private List<LineageNode> nodes;
        private List<LineageEdge> edges;
    }

    @Data
    public static class LineageNode {
        private Long tableId;
        private String id;
        private String name;
        private String layer;
        private String comment;
        private String businessDomain;
        private String dataDomain;
        private Long clusterId;
        private String dbName;
    }

    @Data
    public static class LineageEdge {
        private String source;
        private String target;
        private Long taskId;
    }
}
