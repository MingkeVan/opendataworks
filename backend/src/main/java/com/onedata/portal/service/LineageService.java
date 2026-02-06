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
     * 获取血缘图数据（支持筛选 & 以表为中心）
     *
     * @param tableId 以该表为中心展示血缘（优先级高于其它筛选）
     * @param depth 关联层级（-1 表示不限层级）
     */
    public LineageGraph getLineageGraph(
        String layer,
        String businessDomain,
        String dataDomain,
        String keyword,
        Long clusterId,
        String dbName,
        Long tableId,
        Integer depth
    ) {
        if (tableId != null) {
            return getCenteredLineageGraph(tableId, depth);
        }
        return getFilteredLineageGraph(layer, businessDomain, dataDomain, keyword, clusterId, dbName);
    }

    private LineageGraph getFilteredLineageGraph(
        String layer,
        String businessDomain,
        String dataDomain,
        String keyword,
        Long clusterId,
        String dbName
    ) {
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<>();
        if (layer != null && !layer.isEmpty()) {
            tableWrapper.eq(DataTable::getLayer, layer.toUpperCase());
        }
        if (businessDomain != null && !businessDomain.isEmpty()) {
            tableWrapper.eq(DataTable::getBusinessDomain, businessDomain);
        }
        if (dataDomain != null && !dataDomain.isEmpty()) {
            tableWrapper.eq(DataTable::getDataDomain, dataDomain);
        }
        if (clusterId != null) {
            tableWrapper.eq(DataTable::getClusterId, clusterId);
        }
        if (dbName != null && !dbName.isEmpty()) {
            tableWrapper.eq(DataTable::getDbName, dbName);
        }
        if (keyword != null && !keyword.isEmpty()) {
            tableWrapper.and(w -> w.like(DataTable::getTableName, keyword)
                .or().like(DataTable::getTableComment, keyword));
        }

        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);
        if (tables.isEmpty()) {
            return emptyGraph();
        }

        Map<Long, DataTable> tableMap = tables.stream()
            .collect(Collectors.toMap(DataTable::getId, t -> t));

        Set<Long> tableIds = new HashSet<>(tableMap.keySet());

        List<DataLineage> lineages = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .and(wrapper -> wrapper.in(DataLineage::getUpstreamTableId, tableIds)
                    .or()
                    .in(DataLineage::getDownstreamTableId, tableIds))
        );

        List<LineageNode> nodes = tables.stream()
            .map(this::buildLineageNode)
            .collect(Collectors.toList());

        // 按任务分组血缘关系
        Map<Long, List<DataLineage>> lineagesByTask = lineages.stream()
            .collect(Collectors.groupingBy(DataLineage::getTaskId));

        List<LineageEdge> edges = new ArrayList<>();
        for (Map.Entry<Long, List<DataLineage>> entry : lineagesByTask.entrySet()) {
            Long taskId = entry.getKey();
            List<DataLineage> taskLineages = entry.getValue();

            // 找出该任务的输入表和输出表
            Set<Long> inputTableIds = taskLineages.stream()
                .filter(l -> "input".equals(l.getLineageType()) && l.getUpstreamTableId() != null)
                .map(DataLineage::getUpstreamTableId)
                .collect(Collectors.toSet());

            Set<Long> outputTableIds = taskLineages.stream()
                .filter(l -> "output".equals(l.getLineageType()) && l.getDownstreamTableId() != null)
                .map(DataLineage::getDownstreamTableId)
                .collect(Collectors.toSet());

            // 为每个输入表到输出表创建边
            for (Long upstreamId : inputTableIds) {
                for (Long downstreamId : outputTableIds) {
                    DataTable upstream = tableMap.get(upstreamId);
                    DataTable downstream = tableMap.get(downstreamId);
                    if (upstream != null && downstream != null) {
                        LineageEdge edge = new LineageEdge();
                        edge.setSource(String.valueOf(upstream.getId()));
                        edge.setTarget(String.valueOf(downstream.getId()));
                        edge.setTaskId(taskId);
                        edges.add(edge);
                    }
                }
            }
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private LineageGraph getCenteredLineageGraph(Long rootTableId, Integer depth) {
        DataTable root = dataTableMapper.selectById(rootTableId);
        if (root == null) {
            return emptyGraph();
        }

        int maxDepth = depth == null ? 2 : depth;
        boolean unlimited = maxDepth < 0;

        Set<Long> visited = new HashSet<>();
        Set<Long> frontier = new HashSet<>();

        visited.add(rootTableId);
        frontier.add(rootTableId);

        Map<Long, TaskTables> tasksById = new HashMap<>();

        int steps = 0;
        while (!frontier.isEmpty() && (unlimited || steps < maxDepth)) {
            Set<Long> taskIds = findTaskIdsByTables(frontier);
            if (taskIds.isEmpty()) break;

            Set<Long> missingTaskIds = taskIds.stream()
                .filter(taskId -> !tasksById.containsKey(taskId))
                .collect(Collectors.toSet());
            if (!missingTaskIds.isEmpty()) {
                loadTasks(tasksById, missingTaskIds);
            }

            Set<Long> neighborCandidates = new HashSet<>();
            for (Long taskId : taskIds) {
                TaskTables tables = tasksById.get(taskId);
                if (tables == null) continue;

                if (intersects(frontier, tables.inputTableIds)) {
                    neighborCandidates.addAll(tables.outputTableIds);
                }
                if (intersects(frontier, tables.outputTableIds)) {
                    neighborCandidates.addAll(tables.inputTableIds);
                }
            }

            Set<Long> nextFrontier = new HashSet<>();
            for (Long candidate : neighborCandidates) {
                if (candidate == null) continue;
                if (visited.add(candidate)) {
                    nextFrontier.add(candidate);
                }
            }

            frontier = nextFrontier;
            steps++;
        }

        // Load all tasks among the visited tables so edges are complete within the subgraph.
        Set<Long> allTaskIds = findTaskIdsByTables(visited);
        Set<Long> missingTaskIds = allTaskIds.stream()
            .filter(taskId -> !tasksById.containsKey(taskId))
            .collect(Collectors.toSet());
        if (!missingTaskIds.isEmpty()) {
            loadTasks(tasksById, missingTaskIds);
        }

        List<DataTable> tables = dataTableMapper.selectBatchIds(visited);
        if (tables.isEmpty()) return emptyGraph();

        Map<Long, DataTable> tableMap = tables.stream()
            .collect(Collectors.toMap(DataTable::getId, t -> t));

        List<LineageNode> nodes = tables.stream()
            .map(this::buildLineageNode)
            .collect(Collectors.toList());

        List<LineageEdge> edges = buildEdgesForTasks(tasksById, visited, tableMap);

        LineageGraph graph = new LineageGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private Set<Long> findTaskIdsByTables(Set<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) return Collections.emptySet();

        List<DataLineage> rows = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .select(DataLineage::getTaskId)
                .and(wrapper -> wrapper.in(DataLineage::getUpstreamTableId, tableIds)
                    .or()
                    .in(DataLineage::getDownstreamTableId, tableIds))
        );

        Set<Long> taskIds = new HashSet<>();
        for (DataLineage row : rows) {
            if (row.getTaskId() != null) taskIds.add(row.getTaskId());
        }
        return taskIds;
    }

    private void loadTasks(Map<Long, TaskTables> tasksById, Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return;

        List<DataLineage> rows = dataLineageMapper.selectList(
            new LambdaQueryWrapper<DataLineage>()
                .in(DataLineage::getTaskId, taskIds)
        );

        Map<Long, List<DataLineage>> byTask = rows.stream()
            .filter(r -> r.getTaskId() != null)
            .collect(Collectors.groupingBy(DataLineage::getTaskId));

        for (Map.Entry<Long, List<DataLineage>> entry : byTask.entrySet()) {
            Long taskId = entry.getKey();
            if (tasksById.containsKey(taskId)) continue;

            Set<Long> inputTableIds = entry.getValue().stream()
                .filter(l -> "input".equals(l.getLineageType()) && l.getUpstreamTableId() != null)
                .map(DataLineage::getUpstreamTableId)
                .collect(Collectors.toSet());

            Set<Long> outputTableIds = entry.getValue().stream()
                .filter(l -> "output".equals(l.getLineageType()) && l.getDownstreamTableId() != null)
                .map(DataLineage::getDownstreamTableId)
                .collect(Collectors.toSet());

            TaskTables tables = new TaskTables();
            tables.setTaskId(taskId);
            tables.setInputTableIds(inputTableIds);
            tables.setOutputTableIds(outputTableIds);
            tasksById.put(taskId, tables);
        }
    }

    private List<LineageEdge> buildEdgesForTasks(
        Map<Long, TaskTables> tasksById,
        Set<Long> allowedTableIds,
        Map<Long, DataTable> tableMap
    ) {
        if (tasksById == null || tasksById.isEmpty()) return Collections.emptyList();

        List<LineageEdge> edges = new ArrayList<>();
        for (Map.Entry<Long, TaskTables> entry : tasksById.entrySet()) {
            TaskTables tables = entry.getValue();
            if (tables == null) continue;

            for (Long upstreamId : tables.getInputTableIds()) {
                if (upstreamId == null || !allowedTableIds.contains(upstreamId)) continue;
                for (Long downstreamId : tables.getOutputTableIds()) {
                    if (downstreamId == null || !allowedTableIds.contains(downstreamId)) continue;
                    if (!tableMap.containsKey(upstreamId) || !tableMap.containsKey(downstreamId)) continue;

                    LineageEdge edge = new LineageEdge();
                    edge.setSource(String.valueOf(upstreamId));
                    edge.setTarget(String.valueOf(downstreamId));
                    edge.setTaskId(tables.getTaskId());
                    edges.add(edge);
                }
            }
        }
        return edges;
    }

    private boolean intersects(Set<Long> a, Set<Long> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Set<Long> smaller = a.size() <= b.size() ? a : b;
        Set<Long> larger = smaller == a ? b : a;
        for (Long id : smaller) {
            if (id != null && larger.contains(id)) return true;
        }
        return false;
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
        return node;
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
    }

    @Data
    public static class LineageEdge {
        private String source;
        private String target;
        private Long taskId;
    }

    @Data
    private static class TaskTables {
        private Long taskId;
        private Set<Long> inputTableIds;
        private Set<Long> outputTableIds;
    }
}
