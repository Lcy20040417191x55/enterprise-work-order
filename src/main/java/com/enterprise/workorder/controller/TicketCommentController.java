package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.CommentCreateRequest;
import com.enterprise.workorder.dto.CommentVO;
import com.enterprise.workorder.service.TicketCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单评论接口。
 *
 * <p>路径挂在 {@code /api/tickets/{ticketId}/comments} 之下，而不是独立成
 * {@code /api/comments}：评论从属于工单，url 结构反映这个从属关系后，
 * 权限校验的入口也就自然带上 ticketId 了。若做成 {@code /api/comments?id=}，
 * 每次都要额外查一次"这条评论属于哪张单"才能判断可见性。</p>
 */
@Tag(name = "工单评论", description = "工单下的讨论，独立于审批意见")
@RestController
@RequestMapping("/api/tickets/{ticketId}/comments")
@RequiredArgsConstructor
public class TicketCommentController {

    private final TicketCommentService commentService;

    @Operation(summary = "工单评论列表", description = "按时间正序；能看工单详情的人都能看评论")
    @GetMapping
    public Result<List<CommentVO>> list(@PathVariable Long ticketId) {
        return Result.success(commentService.list(ticketId));
    }

    @Operation(summary = "新增评论", description = "会通知工单参与人（创建人、待办人、历史审批人）")
    @PostMapping
    public Result<CommentVO> create(@PathVariable Long ticketId,
                                    @Valid @RequestBody CommentCreateRequest request) {
        return Result.success("评论已发表", commentService.create(ticketId, request));
    }

    @Operation(summary = "撤回评论", description = "只能撤回自己发表的评论")
    @DeleteMapping("/{commentId}")
    public Result<Void> delete(@PathVariable Long ticketId,
                               @PathVariable Long commentId) {
        commentService.delete(ticketId, commentId);
        return Result.success("评论已撤回", null);
    }
}
