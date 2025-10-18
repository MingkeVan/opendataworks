package com.onedata.portal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据门户主应用
 */
@SpringBootApplication
@MapperScan("com.onedata.portal.mapper")
public class DataPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataPortalApplication.class, args);
    }
}
