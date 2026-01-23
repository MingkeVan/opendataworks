package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DolphinScheduler task group information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinTaskGroup {

    private Integer id;
    private String name;
    private String description;
    private Integer groupSize;
    private Integer useSize;
    private Integer status;
    private String createTime;
    private String updateTime;
}

