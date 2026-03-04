package com.onedata.portal.service.assistant.nl2lf;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DefaultLfSqlCompiler implements LfSqlCompiler {

    @Override
    public CompiledSql compile(LogicalForm lf) {
        CompiledSql compiledSql = new CompiledSql();
        if (lf == null) {
            compiledSql.setSql(null);
            compiledSql.setExplain("LF 为空，无法编译 SQL");
            compiledSql.setLineageUsed(false);
            return compiledSql;
        }

        compiledSql.setSql(lf.getSqlDraft());
        if (!StringUtils.hasText(lf.getSqlDraft())) {
            compiledSql.setExplain("LF 缺少 SQL 草稿");
            compiledSql.setLineageUsed(false);
            return compiledSql;
        }

        boolean lineageUsedByTrace = Boolean.TRUE.equals(lf.getTrace().get("lineageUsed"));
        compiledSql.setExplain("LF 编译完成（metadata+lineage）");
        compiledSql.setLineageUsed(lineageUsedByTrace || !lf.getJoins().isEmpty());
        return compiledSql;
    }
}
