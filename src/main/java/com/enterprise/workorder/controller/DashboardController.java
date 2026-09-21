package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.DashboardVO;
import com.enterprise.workorder.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页数据看板接口。
 *
 * <p>只有一个接口、一次返回全部数据，而不是拆成 summary / distribution / trend 三个：
 * 看板是三张卡片一起呈现的，拆开就意味着三次并发请求和三个 loading 态，
 * 用户会先看到数字卡片、几秒后再看到图表。合成一次请求后整屏同时出现。</p>
 */
@Tag(name = "数据看板", description = "首页统计，口径与工单列表的可见范围一致")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "看板总览",
            description = "汇总数字 + 状态分布 + 近7天趋势；普通用户只统计与自己相关的工单")
    @GetMapping("/overview")
    public Result<DashboardVO> overview() {
        return Result.success(dashboardService.overview());
    }
}
