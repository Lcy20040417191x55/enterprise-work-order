package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查。用于确认服务是否起来、数据库连接是否正常。
 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "服务健康检查")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of(
                "status", "UP",
                "service", "enterprise-work-order",
                "time", LocalDateTime.now().toString()));
    }
}