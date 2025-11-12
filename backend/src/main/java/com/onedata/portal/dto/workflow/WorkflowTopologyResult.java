package com.onedata.portal.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 工作流拓扑分析结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTopologyResult {

    private Map<Long, Set<Long>> upstreamMap;

    private Map<Long, Set<Long>> downstreamMap;

    private Set<Long> entryTaskIds;

    private Set<Long> exitTaskIds;

    public static WorkflowTopologyResult empty() {
        return WorkflowTopologyResult.builder()
            .upstreamMap(Collections.emptyMap())
            .downstreamMap(Collections.emptyMap())
            .entryTaskIds(Collections.emptySet())
            .exitTaskIds(Collections.emptySet())
            .build();
    }
}
