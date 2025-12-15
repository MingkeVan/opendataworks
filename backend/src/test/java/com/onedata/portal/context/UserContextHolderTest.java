package com.onedata.portal.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserContextHolder测试
 * 测试ThreadLocal的正确性和线程安全性
 */
class UserContextHolderTest {

    @AfterEach
    void tearDown() {
        // 确保每个测试后清理上下文
        UserContextHolder.clear();
    }

    @Test
    void testSetAndGetContext() {
        // 创建用户上下文
        UserContext context = new UserContext("user123", "testuser", "oauth123");
        
        // 设置上下文
        UserContextHolder.setContext(context);
        
        // 获取上下文
        UserContext retrieved = UserContextHolder.getContext();
        
        assertNotNull(retrieved);
        assertEquals("user123", retrieved.getUserId());
        assertEquals("testuser", retrieved.getUsername());
        assertEquals("oauth123", retrieved.getOauthUserId());
    }

    @Test
    void testGetCurrentUserId() {
        UserContext context = new UserContext("user456", "anotheruser", "oauth456");
        UserContextHolder.setContext(context);
        
        String userId = UserContextHolder.getCurrentUserId();
        
        assertEquals("user456", userId);
    }

    @Test
    void testGetCurrentUsername() {
        UserContext context = new UserContext("user789", "thirduser", "oauth789");
        UserContextHolder.setContext(context);
        
        String username = UserContextHolder.getCurrentUsername();
        
        assertEquals("thirduser", username);
    }

    @Test
    void testClearContext() {
        UserContext context = new UserContext("user999", "clearuser", "oauth999");
        UserContextHolder.setContext(context);
        
        assertNotNull(UserContextHolder.getContext());
        
        UserContextHolder.clear();
        
        assertNull(UserContextHolder.getContext());
    }

    @Test
    void testSetNullContext() {
        UserContextHolder.setContext(null);
        
        assertNull(UserContextHolder.getContext());
    }

    @Test
    void testGetContextWhenNotSet() {
        UserContext context = UserContextHolder.getContext();
        
        assertNull(context);
    }

    @Test
    void testThreadIsolation() throws InterruptedException {
        // 主线程设置上下文
        UserContext mainContext = new UserContext("main-user", "mainuser", "oauth-main");
        UserContextHolder.setContext(mainContext);
        
        // 创建另一个线程
        CountDownLatch latch = new CountDownLatch(1);
        List<UserContext> otherThreadContext = new ArrayList<>();
        
        Thread otherThread = new Thread(() -> {
            // 其他线程应该看不到主线程的上下文
            otherThreadContext.add(UserContextHolder.getContext());
            
            // 设置其他线程自己的上下文
            UserContext threadContext = new UserContext("thread-user", "threaduser", "oauth-thread");
            UserContextHolder.setContext(threadContext);
            
            latch.countDown();
        });
        
        otherThread.start();
        latch.await(5, TimeUnit.SECONDS);
        otherThread.join();
        
        // 验证其他线程看不到主线程的上下文
        assertNull(otherThreadContext.get(0));
        
        // 验证主线程的上下文没有被影响
        UserContext mainRetrieved = UserContextHolder.getContext();
        assertNotNull(mainRetrieved);
        assertEquals("main-user", mainRetrieved.getUserId());
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> results = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // 每个线程设置自己的上下文
                    UserContext context = new UserContext(
                            "user-" + threadId,
                            "username-" + threadId,
                            "oauth-" + threadId
                    );
                    UserContextHolder.setContext(context);
                    
                    // 模拟一些工作
                    Thread.sleep(10);
                    
                    // 验证上下文没有被其他线程影响
                    UserContext retrieved = UserContextHolder.getContext();
                    synchronized (results) {
                        results.add(retrieved.getUserId());
                    }
                    
                    UserContextHolder.clear();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        
        // 验证每个线程都获取到了正确的上下文
        assertEquals(threadCount, results.size());
        for (int i = 0; i < threadCount; i++) {
            assertTrue(results.contains("user-" + i));
        }
    }
}
