package com.onedata.portal.dto;

import com.onedata.portal.entity.DataTable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 表匹配响应
 */
@Data
@NoArgsConstructor
public class SqlTableMatchResponse {

    private List<MatchedTable> upstreamMatches = new ArrayList<>();

    private List<MatchedTable> downstreamMatches = new ArrayList<>();

    private List<String> unmatchedUpstream = new ArrayList<>();

    private List<String> unmatchedDownstream = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchedTable {
        private String rawTable;
        private String database;
        private DataTable table;
    }
}
