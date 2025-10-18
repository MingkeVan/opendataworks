package com.onedata.portal.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表关联血缘响应
 */
@Data
public class TableRelatedLineageResponse {

    private List<TableLineageItem> upstreamTables = new ArrayList<>();
    private List<TableLineageItem> downstreamTables = new ArrayList<>();
}
