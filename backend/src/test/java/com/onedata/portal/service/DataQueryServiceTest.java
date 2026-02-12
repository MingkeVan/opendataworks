package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.SqlAnalyzeRequest;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.mapper.DataQueryHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DataQueryServiceTest {

    private DataQueryService service;

    @BeforeEach
    void setUp() {
        service = new DataQueryService(
            mock(DorisConnectionService.class),
            mock(DorisClusterService.class),
            mock(DataQueryHistoryMapper.class),
            new ObjectMapper()
        );
    }

    @Test
    void validateSqlAllowsSemicolonsInsideStringLiterals() {
        assertDoesNotThrow(() -> service.validateSql("SELECT ';drop table' AS col"));
    }

    @Test
    void validateSqlAllowsDangerousWordsInsideComments() {
        String sql = "SELECT * FROM users -- drop table";
        assertDoesNotThrow(() -> service.validateSql(sql));
    }

    @Test
    void validateSqlAllowsMultipleSafeStatements() {
        assertDoesNotThrow(() -> service.validateSql("SELECT 1; SELECT 2"));
    }

    @Test
    void validateSqlRequiresConfirmForDangerousStatement() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.validateSql("DROP TABLE users"));
        org.junit.jupiter.api.Assertions.assertEquals("高风险 SQL 需强确认后执行", ex.getMessage());
    }

    @Test
    void analyzeQueryReturnsConfirmChallengeForDelete() {
        SqlAnalyzeRequest request = new SqlAnalyzeRequest();
        request.setClusterId(1L);
        request.setDatabase("db1");
        request.setSql("DELETE FROM db1.t_user WHERE id = 1");

        SqlAnalyzeResponse response = service.analyzeQuery(request);
        assertFalse(response.isBlocked());
        assertNotNull(response.getConfirmChallenges());
        assertTrue(response.getConfirmChallenges().size() == 1);
        assertNotNull(response.getConfirmChallenges().get(0).getConfirmToken());
    }

    @Test
    void analyzeQueryBlocksWhenTargetCannotBeResolved() {
        SqlAnalyzeRequest request = new SqlAnalyzeRequest();
        request.setClusterId(1L);
        request.setDatabase("db1");
        request.setSql("DELETE");

        SqlAnalyzeResponse response = service.analyzeQuery(request);
        assertTrue(response.isBlocked());
        assertNotNull(response.getBlockedReason());
    }
}
