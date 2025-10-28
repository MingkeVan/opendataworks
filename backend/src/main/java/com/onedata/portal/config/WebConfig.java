package com.onedata.portal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Web 配置
 */
@Slf4j
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> permissiveCorsFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                String origin = request.getHeader("Origin");
                if (origin == null || origin.isEmpty()) {
                    origin = "*";
                }

                String requestHeaders = request.getHeader("Access-Control-Request-Headers");
                String requestMethod = request.getHeader("Access-Control-Request-Method");

                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
                response.setHeader("Access-Control-Allow-Credentials", "true");
                response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");
                response.setHeader("Access-Control-Allow-Headers", requestHeaders != null ? requestHeaders : "*");
                response.setHeader("Access-Control-Expose-Headers", "*");
                response.setHeader("Access-Control-Max-Age", "3600");

                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    log.debug("CORS preflight accepted: origin={}, requestMethod={}, requestHeaders={}",
                            request.getHeader("Origin"), requestMethod, requestHeaders);
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                filterChain.doFilter(request, response);

                if (response.getStatus() == HttpServletResponse.SC_FORBIDDEN && request.getHeader("Origin") != null) {
                    log.warn("CORS request blocked with 403: origin={}, method={}, path={}, allowedHeaders={}",
                            request.getHeader("Origin"), request.getMethod(), request.getRequestURI(), requestHeaders);
                }
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
