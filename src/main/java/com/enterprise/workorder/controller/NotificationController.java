package com.enterprise.workorder.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.NotificationQuery;
import com.enterprise.workorder.dto.NotificationVO;
import com.enterprise.workorder.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知接口。
 *
 * <p><b>本控制器没有任何 userId 参数</b>，这是刻意设计：所有读写都针对当前登录人，
 * 由 Service 从 SecurityContext 取。把 userId 作为请求参数传进来的接口，
 * 只要有一处忘了校验归属，就是"能读别人通知、能标记别人已读"的越权漏洞，
 * 而这类漏洞测试时很难被发现（接口看起来完全正常）。</p>
 */
@Tag(name = "站内通知", description = "工单审批与评论产生的站内提醒")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "分页查询我的通知")
    @GetMapping
    public Result<IPage<NotificationVO>> page(@Valid NotificationQuery query) {
        return Result.success(notificationService.page(query));
    }

    @Operation(summary = "我的未读通知数", description = "顶栏角标用；返回的是纯数字，不是对象")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.unreadCount());
    }

    @Operation(summary = "标记单条通知已读", description = "只能标记自己的通知；重复标记与越权标记都不会报错")
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.success();
    }

    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    public Result<Integer> markAllRead() {
        // 返回影响行数，前端可以据此决定要不要提示"没有未读消息"
        return Result.success(notificationService.markAllRead());
    }
}
