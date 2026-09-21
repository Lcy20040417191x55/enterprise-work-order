package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.CommentCreateRequest;
import com.enterprise.workorder.dto.CommentVO;

import java.util.List;

/**
 * 工单评论服务。
 *
 * <p>评论的可见性与工单一致：能看详情的人才能看评论、发评论。
 * 这个校验复用 {@code TicketService} 的可见性规则，不在这里另写一份 ——
 * 两处规则一旦分叉，就会出现"详情看得到、评论区 403"这类灵异现象。</p>
 */
public interface TicketCommentService {

    /** 查询某工单的评论，按时间正序（评论是对话，正序才读得顺） */
    List<CommentVO> list(Long ticketId);

    /** 新增评论 */
    CommentVO create(Long ticketId, CommentCreateRequest request);

    /** 撤回自己的评论（逻辑删除）。管理员也不能删别人的，保持"谁说的谁负责" */
    void delete(Long ticketId, Long commentId);
}
