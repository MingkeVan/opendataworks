package com.onedata.portal.service.assistant.nl2lf;

import lombok.Data;

@Data
public class CompiledSql {
    private String sql;
    private String explain;
    private boolean lineageUsed;
}
