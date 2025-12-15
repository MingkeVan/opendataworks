package com.onedata.portal.aspect;

import com.onedata.portal.annotation.RequireAuth;
import com.onedata.portal.context.UserContext;
import com.onedata.portal.context.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.beans.factory.annotation.Value;

import javax.servlet.http.HttpServletRequest;

/**
 * 用户认证切面
 * 自动处理@RequireAuth注解标记的方法，从请求中提取用户信息并设置到UserContextHolder
 */
@Slf4j
@Aspect
@Component
public class AuthenticationAspect {

    /**
     * OAuth用户ID请求头名称
     */
    private static final String HEADER_OAUTH_USER_ID = "X-OAuth-User-Id";

    /**
     * 用户名请求头名称
     */
    private static final String HEADER_USERNAME = "X-Username";

    /**
     * 用户ID请求头名称（平台用户ID）
     */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 是否允许匿名登录
     */
    @Value("${auth.anonymous.enabled:false}")
    private boolean anonymousEnabled;

    /**
     * 匿名登录用户ID
     */
    @Value("${auth.anonymous.user-id:anonymous}")
    private String anonymousUserId;

    /**
     * 匿名登录用户名
     */
    @Value("${auth.anonymous.username:Anonymous User}")
    private String anonymousUsername;

    @Around("@annotation(com.onedata.portal.annotation.RequireAuth)")
    public Object handleAuthentication(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequireAuth requireAuth = signature.getMethod().getAnnotation(RequireAuth.class);

        try {
            // 从请求中提取用户信息
            UserContext userContext = extractUserContext();

            // 检查是否必须有用户信息
            if (userContext == null) {
                if (requireAuth.required()) {
                    log.error("User authentication required but no user context found");
                    throw new RuntimeException("用户未认证");
                }
            } else {
                // 设置用户上下文
                UserContextHolder.setContext(userContext);
            }

            // 执行目标方法
            return joinPoint.proceed();

        } finally {
            // 清除用户上下文
            UserContextHolder.clear();
        }
    }

    /**
     * 从HTTP请求中提取用户上下文
     */
    private UserContext extractUserContext() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            log.warn("No request attributes found, cannot extract user context");
            return null;
        }

        HttpServletRequest request = attributes.getRequest();

        // 从请求头中提取用户信息
        String userId = request.getHeader(HEADER_USER_ID);
        String username = request.getHeader(HEADER_USERNAME);
        String oauthUserId = request.getHeader(HEADER_OAUTH_USER_ID);

        // 如果没有用户ID，尝试使用OAuth用户ID作为用户ID
        if (userId == null && oauthUserId != null) {
            userId = oauthUserId;
        }

        // 尝试匿名登录
        if (userId == null) {
            if (anonymousEnabled) {
                userId = anonymousUserId;
                username = anonymousUsername;
                log.debug("Using anonymous login: userId={}, username={}", userId, username);
            } else {
                log.debug("No user ID found in request headers");
                return null;
            }
        }

        UserContext context = new UserContext();
        context.setUserId(userId);
        context.setUsername(username);
        context.setOauthUserId(oauthUserId);

        log.debug("Extracted user context from request: userId={}, username={}", userId, username);

        return context;
    }
}
