package com.onedata.portal.dto.backup;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快照恢复响应
 */
@Data
public class SchemaBackupRestoreResponse {

    private String schemaName;
    private String snapshotName;
    private String backupTimestamp;
    private String tableName;
    private String repositoryName;
    private LocalDateTime submittedAt;
}

