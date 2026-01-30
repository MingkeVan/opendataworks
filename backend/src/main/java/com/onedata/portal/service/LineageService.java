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
        return getLineageGraph(null, null, null, null);
    }

    /**
     * 获取血缘图数据（支持筛选）
     */
    public LineageGraph getLineageGraph(String layer, String businessDomain, String dataDomain, String keyword) {
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
                        edge.setSource(upstream.getTableName());
                        edge.setTarget(downstream.getTableName());
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

    private LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(Collections.emptyList());
        graph.setEdges(Collections.emptyList());
        return graph;
    }

    private LineageNode buildLineageNode(DataTable table) {
        LineageNode node = new LineageNode();
        node.setId(table.getTableName());
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
}
