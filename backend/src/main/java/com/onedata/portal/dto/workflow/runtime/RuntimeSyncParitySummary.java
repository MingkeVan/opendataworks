package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出路径与旧路径一致性摘要
 */
@Data
public class RuntimeSyncParitySummary {

    private String status = "not_checked";

    private Boolean changed = false;

    private String primaryHash;

    private String shadowHash;

    private Integer workflowFieldDiffCount = 0;

    private Integer taskAddedDiffCount = 0;

    private Integer taskRemovedDiffCount = 0;

    private Integer taskModifiedDiffCount = 0;

    private Integer edgeAddedDiffCount = 0;

    private Integer edgeRemovedDiffCount = 0;

    private Integer scheduleDiffCount = 0;

    private List<String> sampleMismatches = new ArrayList<>();
}
