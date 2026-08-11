package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentInspectResponse;
import com.onedata.portal.agentapi.dto.AgentLineageResponse;
import com.onedata.portal.agentapi.dto.AgentMetadataCompleteRequest;
import com.onedata.portal.agentapi.dto.AgentMetadataCompleteResponse;
import com.onedata.portal.agentapi.dto.AgentTableDdlResponse;

import java.util.List;
import java.util.Map;

public interface AgentMetadataService {

    AgentInspectResponse inspect(String database, String table, String keyword, int tableLimit);

    AgentLineageResponse lineage(String table, String dbName, Long tableId, Integer depth);

    AgentDatasourceResolution resolveDatasource(String database, String preferredEngine);

    AgentTableDdlResponse ddl(String database, String table, Long tableId);

    List<Map<String, Object>> exportTables(String database);

    List<Map<String, Object>> exportLineage(String database);

    List<Map<String, Object>> exportDatasource(String database);

    /**
     * 完善一张已存在表的元数据：表描述、受控属性、逐字段注释、数据新鲜度契约。
     * 各段独立应用、非事务，逐段回报 applied / skipped / failed。
     */
    AgentMetadataCompleteResponse complete(AgentMetadataCompleteRequest request, String operator);
}
