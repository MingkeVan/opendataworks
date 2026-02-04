package com.onedata.portal.dto;

import lombok.Data;

import java.util.List;

/**
 * DolphinScheduler 环境选项
 */
@Data
public class DolphinEnvironmentOption {

    private Long code;

    private String name;

    private List<String> workerGroups;
}

