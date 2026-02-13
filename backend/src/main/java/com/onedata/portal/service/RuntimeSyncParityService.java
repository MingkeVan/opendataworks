package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncParitySummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运行态导出路径与旧路径一致性比对服务
 */
@Service
@RequiredArgsConstructor
public class RuntimeSyncParityService {

    public static final String STATUS_NOT_CHECKED = "not_checked";
    public static final String STATUS_CONSISTENT = "consistent";
    public static final String STATUS_INCONSISTENT = "inconsistent";

    private static final int MAX_SAMPLE_MISMATCHES = 12;

    private final WorkflowRuntimeDiffService runtimeDiffService;
    private final ObjectMapper objectMapper;

    public RuntimeSyncParitySummary compare(RuntimeWorkflowDefinition primary, RuntimeWorkflowDefinition shadow) {
        RuntimeSyncParitySummary summary = new RuntimeSyncParitySummary();
        summary.setStatus(STATUS_NOT_CHECKED);
        summary.setChanged(false);

        if (primary == null || shadow == null) {
            return summary;
        }

        WorkflowRuntimeDiffService.RuntimeSnapshot primarySnapshot =
                runtimeDiffService.buildSnapshot(primary, normalizeEdges(primary.getExplicitEdges()));
        WorkflowRuntimeDiffService.RuntimeSnapshot shadowSnapshot =
                runtimeDiffService.buildSnapshot(shadow, normalizeEdges(shadow.getExplicitEdges()));

        RuntimeDiffSummary diff = runtimeDiffService.buildDiff(shadowSnapshot.getSnapshotJson(), primarySnapshot);
        summary.setPrimaryHash(primarySnapshot.getSnapshotHash());
        summary.setShadowHash(shadowSnapshot.getSnapshotHash());
        summary.setWorkflowFieldDiffCount(size(diff.getWorkflowFieldChanges()));
        summary.setTaskAddedDiffCount(size(diff.getTaskAdded()));
        summary.setTaskRemovedDiffCount(size(diff.getTaskRemoved()));
        summary.setTaskModifiedDiffCount(size(diff.getTaskModified()));
        summary.setEdgeAddedDiffCount(size(diff.getEdgeAdded()));
        summary.setEdgeRemovedDiffCount(size(diff.getEdgeRemoved()));
        summary.setScheduleDiffCount(size(diff.getScheduleChanges()));

        int mismatchCount = summary.getWorkflowFieldDiffCount()
                + summary.getTaskAddedDiffCount()
                + summary.getTaskRemovedDiffCount()
                + summary.getTaskModifiedDiffCount()
                + summary.getEdgeAddedDiffCount()
                + summary.getEdgeRemovedDiffCount()
                + summary.getScheduleDiffCount();
        summary.setChanged(mismatchCount > 0);
        summary.setStatus(mismatchCount > 0 ? STATUS_INCONSISTENT : STATUS_CONSISTENT);
        summary.setSampleMismatches(buildSampleMismatches(diff));
        return summary;
    }

    public String toJson(RuntimeSyncParitySummary summary) {
        if (summary == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public RuntimeSyncParitySummary parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RuntimeSyncParitySummary.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public boolean isInconsistent(String status) {
        return STATUS_INCONSISTENT.equalsIgnoreCase(String.valueOf(status));
    }

    private int size(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private List<String> buildSampleMismatches(RuntimeDiffSummary diff) {
        if (diff == null) {
            return Collections.emptyList();
        }
        List<String> samples = new ArrayList<>();
        appendSamples(samples, "workflow", diff.getWorkflowFieldChanges());
        appendSamples(samples, "task_added", diff.getTaskAdded());
        appendSamples(samples, "task_removed", diff.getTaskRemoved());
        appendSamples(samples, "task_modified", diff.getTaskModified());
        appendSamples(samples, "edge_added", diff.getEdgeAdded());
        appendSamples(samples, "edge_removed", diff.getEdgeRemoved());
        appendSamples(samples, "schedule", diff.getScheduleChanges());
        return samples;
    }

    private void appendSamples(List<String> collector, String section, List<String> items) {
        if (CollectionUtils.isEmpty(items) || collector.size() >= MAX_SAMPLE_MISMATCHES) {
            return;
        }
        for (String item : items) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            collector.add(section + ": " + item);
            if (collector.size() >= MAX_SAMPLE_MISMATCHES) {
                return;
            }
        }
    }

    private List<RuntimeTaskEdge> normalizeEdges(List<RuntimeTaskEdge> edges) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RuntimeTaskEdge> normalized = new ArrayList<>();
        for (RuntimeTaskEdge edge : edges) {
            if (edge == null || edge.getUpstreamTaskCode() == null || edge.getDownstreamTaskCode() == null) {
                continue;
            }
            String key = edge.getUpstreamTaskCode() + "->" + edge.getDownstreamTaskCode();
            if (!seen.add(key)) {
                continue;
            }
            normalized.add(new RuntimeTaskEdge(edge.getUpstreamTaskCode(), edge.getDownstreamTaskCode()));
        }
        normalized.sort(Comparator
                .comparing(RuntimeTaskEdge::getUpstreamTaskCode, Comparator.nullsLast(Long::compareTo))
                .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode, Comparator.nullsLast(Long::compareTo)));
        return normalized;
    }
}
