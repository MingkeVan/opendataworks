package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.mapper.DataQueryHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void validateSqlRejectsMultipleStatements() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.validateSql("SELECT 1; SELECT 2"));
        org.junit.jupiter.api.Assertions.assertEquals("仅支持单条 SQL 执行", ex.getMessage());
    }

    @Test
    void validateSqlRejectsDangerousKeywordOutsideLiterals() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.validateSql("DROP TABLE users"));
        org.junit.jupiter.api.Assertions.assertEquals("检测到危险 SQL 关键字，请检查后再执行", ex.getMessage());
    }
}
