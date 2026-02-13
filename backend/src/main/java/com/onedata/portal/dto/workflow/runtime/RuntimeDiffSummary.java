package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行态差异摘要
 */
@Data
public class RuntimeDiffSummary {

    private String baselineHash;

    private String currentHash;

    private Boolean changed = false;

    private List<String> workflowFieldChanges = new ArrayList<>();

    private List<String> taskAdded = new ArrayList<>();

    private List<String> taskRemoved = new ArrayList<>();

    private List<String> taskModified = new ArrayList<>();

    private List<String> edgeAdded = new ArrayList<>();

    private List<String> edgeRemoved = new ArrayList<>();

    private List<String> scheduleChanges = new ArrayList<>();
}
