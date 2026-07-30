package com.onedata.portal.service.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisAuditSqlTableParserTest {

    private final DorisAuditSqlTableParser parser = new DorisAuditSqlTableParser();

    @Test
    void classifiesInsertSelectSourcesAndTargetWithoutDoubleCounting() {
        List<AuditTableReference> references = parser.parse(
                "INSERT INTO ads.order_summary "
                        + "SELECT a.id FROM dwd.orders a JOIN dim.users u ON a.user_id = u.id "
                        + "JOIN dwd.orders b ON b.id = a.id",
                "default_db");

        Map<String, AuditTableReference> index = references.stream()
                .collect(Collectors.toMap(
                        item -> item.getDatabaseName() + "." + item.getTableName(),
                        item -> item));

        assertEquals(3, index.size());
        assertTrue(index.get("ads.order_summary").isWrite());
        assertFalse(index.get("ads.order_summary").isRead());
        assertTrue(index.get("dwd.orders").isRead());
        assertFalse(index.get("dwd.orders").isWrite());
        assertTrue(index.get("dim.users").isRead());
    }

    @Test
    void usesDefaultDatabaseAndClassifiesUpdateAndDeleteAsWrites() {
        List<AuditTableReference> references = parser.parse(
                "UPDATE orders SET status = 1; DELETE FROM order_items WHERE id = 2",
                "DWD");

        assertEquals(2, references.size());
        assertTrue(references.stream().allMatch(AuditTableReference::isWrite));
        assertTrue(references.stream().allMatch(item -> "dwd".equals(item.getDatabaseName())));
    }
}
