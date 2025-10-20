package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 查询结果预览
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryPreview {
    private List<String> columns;
    private List<Map<String, Object>> rows;
}
