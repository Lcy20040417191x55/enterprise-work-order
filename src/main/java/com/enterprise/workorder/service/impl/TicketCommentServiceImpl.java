package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.CommentCreateRequest;
import com.enterprise.workorder.dto.CommentVO;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketComment;
import com.enterprise.workorder.enums.TicketOpAction;
import com.enterprise.workorder.mapper.TicketCommentMapper;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.NotificationService;
import com.enterprise.workorder.service.TicketCommentService;
import com.enterprise.workorder.service.TicketOpLogService;
import com.enterprise.workorder.service.TicketService;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工单评论实现。
 *
 * <p><b>本类与 TicketServiceImpl 的边界</b>：评论不该、也不需要去改工单状态，
 * 所以这里既不持有行锁，也不碰 ticket 表的状态字段。它只做三件事：
 * 校验"你能不能看这张单"（委托给 {@link TicketService}，保持规则唯一）、
 * 读写评论表、触发通知。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketCommentServiceImpl implements TicketCommentService {

    /**
     * 日志摘要保留的字符数。
     *
     * <p>取 120 而不是把 detail 的 500 用满：时间线一行能舒服读完的长度大约就是几十个字，
     * 写成 500 会在详情页右侧那栏里折成十几行，把真正的关键信息（谁、做了什么）淹掉。
     * 想看全文的人本来就有讨论区可看，日志只需要"提示这里发生过什么"。</p>
     */
    private static final int COMMENT_SUMMARY_MAX = 120;

    private final TicketCommentMapper commentMapper;
    private final TicketMapper ticketMapper;
    private final TicketService ticketService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final TicketOpLogService opLogService;

    @Override
    public List<CommentVO> list(Long ticketId) {
        // 能看详情才能看评论。这里直接复用 TicketService 的可见性判定，
        // 而不是在本地再写一遍"创建人 || 管理员 || ..."—— 那份规则改一次就要同步两处，
        // 漏改的结果是"详情页进得去、评论区 403"，排查时非常费解。
        ticketService.requireVisible(ticketId);

        List<TicketComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<TicketComment>()
                        .eq(TicketComment::getTicketId, ticketId)
                        .orderByAsc(TicketComment::getId));
        return comments.stream().map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentVO create(Long ticketId, CommentCreateRequest request) {
        ticketService.requireVisible(ticketId);

        LoginUser current = SecurityUtils.getLoginUser();
        Ticket ticket = ticketMapper.selectById(ticketId);

        TicketComment comment = new TicketComment();
        comment.setTicketId(ticketId);
        comment.setUserId(current.getUserId());
        comment.setUserName(displayNameOf(current));
        // 去掉首尾空白：全是空格的评论没有意义，而 @NotBlank 拦不住" 内容 "这种
        // 前后带空格的输入，落库前统一收口，避免数据显示上看起来一样、比较时却不相等。
        comment.setContent(request.getContent().trim());
        commentMapper.insert(comment);

        // 通知放在状态变更之外，且失败不能影响评论本身：
        // 评论已经落库了，若因为通知表出问题而整体回滚，用户会觉得"我明明发了评论却没了"。
        // 因此这里吞掉异常并记日志 —— 通知是附属功能，绝不该反过来阻断主流程。
        try {
            notificationService.notifyComment(ticketId, current.getUserId(),
                    ticket.getTicketNo(), ticket.getTitle(), comment.getUserName());
        } catch (Exception e) {
            log.warn("工单 {} 的评论通知发送失败，评论本身已保存", ticketId, e);
        }

        // 日志与通知的失败策略刻意不同：通知失败上面被吞掉了，日志失败则让整个事务回滚。
        // 评论本身并不是审计凭证（作者随时可以撤回它），但"某人某时在单上说过话"这件事是 ——
        // 若只落评论不留日志，一条被撤回的评论就彻底消失了，事后无法回答
        // "这张单在驳回前到底沟通过什么"。
        opLogService.record(ticketId, TicketOpAction.COMMENT, summaryOf(comment.getContent()));

        log.info("工单 {} 新增评论 by {}", ticket.getTicketNo(), current.getUsername());
        return toVO(comment);
    }

    /**
     * 撤回评论。
     *
     * <p><b>为什么管理员也不能删别人的评论</b>：审批系统里评论可能作为
     * "我说过什么"的凭证。若管理员能替别人删，争议发生时就没有中立记录了。
     * 确实需要处理违规内容的场景，应该走管理后台的单独操作（并留操作日志），
     * 而不是让它混在普通用户接口里。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long ticketId, Long commentId) {
        ticketService.requireVisible(ticketId);

        TicketComment comment = commentId == null ? null : commentMapper.selectById(commentId);
        if (comment == null || !ticketId.equals(comment.getTicketId())) {
            // 注意两个条件都要判：评论存在但属于另一张单时，若只用 id 查询就会
            // 出现"用 A 单的权限删除 B 单评论"的越权路径。
            throw new BusinessException(ResultCode.NOT_FOUND, "评论不存在");
        }

        LoginUser current = SecurityUtils.getLoginUser();
        if (!comment.getUserId().equals(current.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能撤回自己发表的评论");
        }

        commentMapper.deleteById(commentId);

        // 撤回评论也要留痕：日志里保留内容摘要，而评论表里那条记录已经逻辑删除。
        // 这正是"两份数据"的价值 —— 页面上不再显示这条评论（尊重作者的撤回意图），
        // 但审计角度仍能追溯"这里曾经有过一次沟通，后来被撤回了"。
        opLogService.record(ticketId, TicketOpAction.COMMENT_DELETE, summaryOf(comment.getContent()));

        log.info("评论 {} 已被作者撤回", commentId);
    }

    /**
     * 评论正文的日志摘要。只取前若干字，单行显示。
     *
     * <p><b>为什么要截断</b>：评论可以写 1000 字，而日志的 detail 列是 500 字。
     * 若不截断，长评论会让日志写入失败，而失败发生在"评论已保存"之后 ——
     * 用户看到的是发布失败，实际评论却已经进库了，这类"看起来失败其实成功"的现象最难排查。
     * 截断在这里比在日志组件里做更准确：只有这里知道这段文字是"评论摘要"而不是别的什么。</p>
     *
     * <p>把换行折叠成空格：日志是时间线上的一行，正文里的换行会让它撑成不规则的多行块，
     * 后续条目被挤得看不出层级。</p>
     */
    private String summaryOf(String content) {
        if (content == null) {
            return null;
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= COMMENT_SUMMARY_MAX
                ? oneLine
                : oneLine.substring(0, COMMENT_SUMMARY_MAX) + "…";
    }

    /** 取显示名。用户被删/查不到时回落到登录名，不让快照字段变成空字符串 */
    private String displayNameOf(LoginUser current) {
        SysUser user = userService.getById(current.getUserId());
        if (user != null && user.getRealName() != null && !user.getRealName().isBlank()) {
            return user.getRealName();
        }
        return current.getUsername();
    }

    private CommentVO toVO(TicketComment comment) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setTicketId(comment.getTicketId());
        vo.setUserId(comment.getUserId());
        vo.setUserName(comment.getUserName());
        vo.setContent(comment.getContent());
        vo.setCreatedAt(comment.getCreatedAt());
        // 前端只用来决定是否显示"撤回"按钮；真正的权限在后端 delete 里再校验一次
        LoginUser current = SecurityUtils.getLoginUserOrNull();
        vo.setDeletable(current != null && comment.getUserId().equals(current.getUserId()));
        return vo;
    }
}
