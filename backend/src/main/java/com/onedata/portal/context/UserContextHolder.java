package com.onedata.portal.context;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户上下文持有者
 * 使用ThreadLocal存储当前线程的用户上下文
 */
@Slf4j
public class UserContextHolder {
    
    private static final ThreadLocal<UserContext> CONTEXT_HOLDER = new ThreadLocal<>();
    
    /**
     * 设置当前用户上下文
     */
    public static void setContext(UserContext context) {
        if (context == null) {
            log.warn("Attempting to set null user context");
            return;
        }
        CONTEXT_HOLDER.set(context);
        log.debug("User context set: userId={}, username={}", context.getUserId(), context.getUsername());
    }
    
    /**
     * 获取当前用户上下文
     */
    public static UserContext getContext() {
        UserContext context = CONTEXT_HOLDER.get();
        if (context == null) {
            log.warn("No user context found in current thread");
        }
        return context;
    }
    
    /**
     * 获取当前用户ID
     */
    public static String getCurrentUserId() {
        UserContext context = getContext();
        return context != null ? context.getUserId() : null;
    }
    
    /**
     * 获取当前用户名
     */
    public static String getCurrentUsername() {
        UserContext context = getContext();
        return context != null ? context.getUsername() : null;
    }
    
    /**
     * 清除当前用户上下文
     */
    public static void clear() {
        UserContext context = CONTEXT_HOLDER.get();
        if (context != null) {
            log.debug("Clearing user context: userId={}", context.getUserId());
        }
        CONTEXT_HOLDER.remove();
    }
}
