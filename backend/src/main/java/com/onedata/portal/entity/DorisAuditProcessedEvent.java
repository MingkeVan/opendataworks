package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("doris_audit_processed_event")
public class DorisAuditProcessedEvent {

    private Long clusterId;
    private String eventKey;
    private LocalDateTime eventTime;
    private LocalDateTime processedAt;
}
