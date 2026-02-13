package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 显式边与血缘推断边不一致详情
 */
@Data
public class RuntimeEdgeMismatchDetail {

    /**
     * Dolphin 显式边集合，格式: upstream->downstream
     */
    private List<String> explicitEdges = new ArrayList<>();

    /**
     * 血缘推断边集合，格式: upstream->downstream
     */
    private List<String> inferredEdges = new ArrayList<>();

    /**
     * 仅出现在 Dolphin 显式边中的差异
     */
    private List<String> onlyInExplicit = new ArrayList<>();

    /**
     * 仅出现在血缘推断边中的差异
     */
    private List<String> onlyInInferred = new ArrayList<>();
}
