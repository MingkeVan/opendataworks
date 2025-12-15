package com.onedata.portal.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限授予请求
 */
@Data
public class PermissionGrantRequest {
    
    private String userId;
    
    private Long clusterId;
    
    private String databaseName;
    
    private List<String> databaseNames; // 批量授权时使用
    
    private String permissionLevel; // readonly, readwrite
    
    private LocalDateTime expiresAt; // 可选的过期时间
}
