package com.onedata.portal.util;

import org.springframework.util.StringUtils;

/**
 * Table name related helpers.
 */
public final class TableNameUtils {

    private TableNameUtils() {
    }

    public static boolean isDeprecatedTableName(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return false;
        }
        return tableName.toLowerCase().contains("deprecated");
    }
}

