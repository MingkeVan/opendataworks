package com.onedata.portal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.onedata.portal.mapper")
@EnableAsync
public class AssistantQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantQueryApplication.class, args);
    }
}
