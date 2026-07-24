package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentTableCreateRequest;

/**
 * Agent-facing table-creation API. Implementations delegate to the platform's
 * engine-aware table-create service so agent-created tables follow the same DDL
 * conventions and metadata bookkeeping as tables created through the UI.
 */
public interface AgentTableService {

    /** Preview only: generate the target table name and normalized DDL, no execution. */
    Object previewCreateTable(AgentTableCreateRequest request, String operator);

    /** Create the table: persist metadata and execute the DDL on the engine. */
    Object createTable(AgentTableCreateRequest request, String operator);
}
