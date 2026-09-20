package com.enterprise.workorder;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业工单与审批系统 启动类
 */
@SpringBootApplication
@MapperScan("com.enterprise.workorder.mapper")
public class WorkOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkOrderApplication.class, args);
    }
}