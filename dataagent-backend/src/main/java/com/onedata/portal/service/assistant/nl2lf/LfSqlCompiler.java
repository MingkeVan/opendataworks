package com.onedata.portal.service.assistant.nl2lf;

public interface LfSqlCompiler {
    CompiledSql compile(LogicalForm lf);
}
