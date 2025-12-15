package com.onedata.portal.aspect;

import com.onedata.portal.annotation.RequireAuth;
import com.onedata.portal.context.UserContext;
import com.onedata.portal.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthenticationAspect测试
 * 测试切面的用户上下文设置和清理功能
 */
@SpringBootTest
class AuthenticationAspectTest {

    @Autowired
    private TestService testService;

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContextHolder.clear();
    }

    @Test
    void testAspectSetsUserContext() {
        // 设置请求头
        request.addHeader("X-User-Id", "test-user-123");
        request.addHeader("X-Username", "testuser");
        request.addHeader("X-OAuth-User-Id", "oauth-123");

        // 调用带@RequireAuth注解的方法
        String result = testService.methodWithAuth();

        // 验证方法执行成功
        assertEquals("success", result);
    }

    @Test
    void testAspectClearsUserContextAfterExecution() {
        // 设置请求头
        request.addHeader("X-User-Id", "test-user-456");
        request.addHeader("X-Username", "anotheruser");

        // 调用方法
        testService.methodWithAuth();

        // 验证上下文已被清理
        assertNull(UserContextHolder.getContext());
    }

    @Test
    void testAspectWithMissingUserIdThrowsException() {
        // 不设置任何用户信息

        // 调用方法应该抛出异常
        assertThrows(RuntimeException.class, () -> testService.methodWithAuth());
    }

    @Test
    void testAspectWithOptionalAuth() {
        // 不设置用户信息

        // 调用可选认证的方法不应该抛出异常
        String result = testService.methodWithOptionalAuth();

        assertEquals("optional-success", result);
    }

    @Test
    void testAspectUsesOAuthUserIdAsFallback() {
        // 只设置OAuth用户ID
        request.addHeader("X-OAuth-User-Id", "oauth-fallback-123");
        request.addHeader("X-Username", "fallbackuser");

        // 调用方法
        String result = testService.methodWithAuth();

        assertEquals("success", result);
    }

    @Test
    void testAspectClearsContextEvenOnException() {
        // 设置请求头
        request.addHeader("X-User-Id", "test-user-789");
        request.addHeader("X-Username", "exceptionuser");

        // 调用会抛出异常的方法
        assertThrows(RuntimeException.class, () -> testService.methodThatThrows());

        // 验证上下文仍然被清理
        assertNull(UserContextHolder.getContext());
    }

    /**
     * 测试服务类
     */
    @Component
    static class TestService {

        @RequireAuth
        public String methodWithAuth() {
            // 验证用户上下文已设置
            UserContext context = UserContextHolder.getContext();
            assertNotNull(context);
            assertNotNull(context.getUserId());
            return "success";
        }

        @RequireAuth(required = false)
        public String methodWithOptionalAuth() {
            return "optional-success";
        }

        @RequireAuth
        public String methodThatThrows() {
            // 验证用户上下文已设置
            UserContext context = UserContextHolder.getContext();
            assertNotNull(context);
            
            // 抛出异常
            throw new RuntimeException("Test exception");
        }
    }
}
