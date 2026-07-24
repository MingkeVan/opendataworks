package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Agent-facing create-table payload. Field names mirror the platform
 * {@code TableCreateRequest} so the backend implementation can map it directly.
 * The target table name is generated from the naming components (layer /
 * businessDomain / ...) by the platform, keeping agent-created tables consistent
 * with tables created through the Data Studio UI. Executing the DDL (as opposed
 * to previewing) requires {@code dorisClusterId}; the backend enforces this.
 */
@Data
public class AgentTableCreateRequest {

    private String layer;

    private String businessDomain;

    private String dataDomain;

    private String customIdentifier;

    private String statisticsCycle;

    private String updateType;

    @NotBlank(message = "dbName 不能为空")
    private String dbName;

    private String tableComment;

    private String owner;

    /** Doris table model: DUPLICATE (default / detail) / AGGREGATE / UNIQUE. */
    private String tableModel;

    private Integer bucketNum;

    private Integer replicaNum;

    private String partitionColumn;

    private List<String> distributionColumns;

    private List<String> keyColumns;

    /** Target Doris cluster/datasource id. Required to execute the DDL. */
    private Long dorisClusterId;

    /** Whether to physically execute the CREATE TABLE on the engine. Defaults to true. */
    private Boolean syncToDoris;

    /** Advanced: a complete Doris CREATE TABLE DDL to use verbatim. */
    private String dorisDdl;

    @NotEmpty(message = "columns 不能为空")
    private List<Column> columns;

    /**
     * Column definition. Field names mirror the platform {@code TableColumnRequest}.
     */
    @Data
    public static class Column {

        @NotBlank(message = "columnName 不能为空")
        private String columnName;

        @NotBlank(message = "dataType 不能为空")
        private String dataType;

        /** Type parameters, e.g. "64" for VARCHAR(64) or "18,2" for DECIMAL(18,2). */
        private String typeParams;

        private Boolean nullable;

        private Boolean primaryKey;

        private Boolean partitionColumn;

        private String defaultValue;

        private String comment;
    }
}
