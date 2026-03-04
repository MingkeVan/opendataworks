package com.onedata.portal.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 需要用户认证的方法标记注解
 * 标记了此注解的方法会自动从请求中提取用户信息并设置到UserContextHolder
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuth {
    
    /**
     * 是否必须有用户信息，默认为true
     * 如果为true且无法获取用户信息，则抛出异常
     */
    boolean required() default true;
}
