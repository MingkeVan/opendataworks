package com.onedata.portal.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户上下文信息
 * 存储当前请求的用户身份信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * OAuth用户ID
     */
    private String oauthUserId;
}
