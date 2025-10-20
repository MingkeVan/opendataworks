package com.onedata.portal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据门户主应用
 */
@SpringBootApplication
@MapperScan("com.onedata.portal.mapper")
@EnableScheduling
@EnableAsync
public class DataPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataPortalApplication.class, args);
    }
}
