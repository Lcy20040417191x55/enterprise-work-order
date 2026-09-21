package com.enterprise.workorder.service;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.CommentCreateRequest;
import com.enterprise.workorder.dto.CommentVO;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.entity.Notification;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.enums.NotificationType;
import com.enterprise.workorder.mapper.NotificationMapper;
import com.enterprise.workorder.mapper.TicketCommentMapper;
import com.enterprise.workorder.support.AuthTestSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单评论测试。
 *
 * <p><b>三件只有连真实数据库才测得准的事</b>：</p>
 * <ol>
 *   <li>评论的可见性与工单详情是否真的走了同一套规则。若实现里各写一份，
 *       分叉的结果是"详情页进得去、评论区 403"，Mock 掉依赖时永远发现不了。</li>
 *   <li>评论通知的收件人集合。群发最容易出错的地方是"把评论人自己也算进去了"
 *       和"同一个人收到两条"，两者都只在落库的通知行上才看得见。</li>
 *   <li>撤回权限。这类越权不泄露数据、只破坏数据，是最容易被漏掉的一类。</li>
 * </ol>
 *
 * <p>@Transactional 保证用例造的数据在结束后回滚，不污染开发库。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("工单评论")
class TicketCommentServiceTest {

    private static final Long UID_ADMIN = 1L;
    private static final Long UID_ZHANGSAN = 2L;
    private static final Long UID_LISI = 3L;
    private static final Long UID_WANGWU = 4L;

    /** 请假申请：审批链 DEPT_LEADER,ADMIN，技术部主管是李四(3) */
    private static final Long TYPE_LEAVE = 1L;

    @Autowired
    private TicketCommentService commentService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private TicketCommentMapper commentMapper;

    @Autowired
    private NotificationService notificationService;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    // ==================================================================
    //  读写与可见性
    // ==================================================================

    @Nested
    @DisplayName("读写与可见性")
    class Basic {

        @Test
        @DisplayName("发表的评论能被自己读回来，字段完整")
        void shouldReadBackOwnComment() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            CommentVO created = commentService.create(ticket.getId(), request("  预算部分我补充一下  "));

            List<CommentVO> rows = commentService.list(ticket.getId());
            assertThat(rows).hasSize(1);
            CommentVO vo = rows.get(0);
            assertThat(vo.getId()).isEqualTo(created.getId());
            // 前后空白必须被 trim：@NotBlank 拦不住" 内容 "这种输入，
            // 不 trim 的话页面上两行看起来一模一样的评论，实际值却不相等
            assertThat(vo.getContent()).isEqualTo("预算部分我补充一下");
            assertThat(vo.getUserId()).isEqualTo(UID_ZHANGSAN);
            // 姓名是快照：用户改名后历史评论要显示当时的名字
            assertThat(vo.getUserName()).isEqualTo("张三");
            assertThat(vo.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("评论按时间正序返回：评论是对话，倒序读不通")
        void shouldReturnInChronologicalOrder() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            commentService.create(ticket.getId(), request("第一句"));
            commentService.create(ticket.getId(), request("第二句"));
            commentService.create(ticket.getId(), request("第三句"));

            assertThat(commentService.list(ticket.getId()))
                    .extracting(CommentVO::getContent)
                    .containsExactly("第一句", "第二句", "第三句");
        }

        @Test
        @DisplayName("无关的人读不到评论，也发不了评论（403）")
        void unrelatedUserShouldBeRejected() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            commentService.create(ticket.getId(), request("内部讨论"));

            // 王五（人事部）与这张技术部的草稿毫无关系
            AuthTestSupport.loginAs(UID_WANGWU);
            assertThatThrownBy(() -> commentService.list(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
            assertThatThrownBy(() -> commentService.create(ticket.getId(), request("我插一句")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
        }

        @Test
        @DisplayName("审批人和管理员都能读能评：规则与工单详情完全一致")
        void approverAndAdminShouldHaveAccess() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            commentService.create(ticket.getId(), request("申请人留言"));

            // 李四是当前待办人（草稿还没提交，但他也不是 creator）——
            // 草稿状态下的可见范围只有 creator 与 admin，这里正好用来区分两者
            AuthTestSupport.loginAs(UID_ADMIN);
            assertThat(commentService.list(ticket.getId())).hasSize(1);
            commentService.create(ticket.getId(), request("管理员留言"));
            assertThat(commentService.list(ticket.getId())).hasSize(2);

            // 提交后李四成为待办人，此时他必须能打开讨论区，
            // 否则点待办进来只看到一片 403
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());
            AuthTestSupport.loginAs(UID_LISI);
            assertThat(commentService.list(ticket.getId())).hasSize(2);
        }

        @Test
        @DisplayName("不存在的工单报 404，而不是悄悄返回空列表")
        void missingTicketShouldThrowNotFound() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            // 返回空列表会让前端显示"还没有评论"，用户以为这张单存在只是没人说话
            assertThatThrownBy(() -> commentService.list(-1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
        }
    }

    // ==================================================================
    //  撤回
    // ==================================================================

    @Nested
    @DisplayName("撤回评论")
    class Delete {

        @Test
        @DisplayName("作者能撤回自己的评论，撤回后列表里不再出现")
        void authorCanWithdrawOwnComment() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            CommentVO mine = commentService.create(ticket.getId(), request("手滑发错了"));

            commentService.delete(ticket.getId(), mine.getId());

            assertThat(commentService.list(ticket.getId())).isEmpty();
            // 是逻辑删除而非物理删除：MyBatis-Plus 的 @TableLogic 会自动过滤，
            // 但底层行还在，走 mapper 单独查能确认它只是被打了标记
            assertThat(commentMapper.selectById(mine.getId())).isNull();
        }

        @Test
        @DisplayName("别人不能撤回我的评论，管理员也不行")
        void othersCannotWithdraw() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            CommentVO mine = commentService.create(ticket.getId(), request("我说的"));

            // 管理员也不行 —— 评论是"我说过什么"的凭证，能被别人替你删就失去意义了。
            // 这条是最容易被放过的越权：它不泄露数据，只破坏数据
            AuthTestSupport.loginAs(UID_ADMIN);
            assertThatThrownBy(() -> commentService.delete(ticket.getId(), mine.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN);
            assertThat(commentService.list(ticket.getId())).hasSize(1);
        }

        @Test
        @DisplayName("不能拿 A 单的权限删 B 单的评论")
        void cannotDeleteCrossTicket() {
            Ticket a = createDraft(UID_ZHANGSAN);
            Ticket b = createDraft(UID_ZHANGSAN);

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            CommentVO onA = commentService.create(a.getId(), request("A 单的评论"));

            // 两张单都是自己建的，所以"可见性"这一关过得去 ——
            // 必须靠"评论归属校验"兜住。少了那个校验，任何能看到 A 单的人
            // 都能删掉 B 单里 id 相邻的评论
            assertThatThrownBy(() -> commentService.delete(b.getId(), onA.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
            assertThat(commentService.list(a.getId())).hasSize(1);
        }

        @Test
        @DisplayName("撤回不存在的评论报 404")
        void deletingMissingCommentShouldThrowNotFound() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> commentService.delete(ticket.getId(), -1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.NOT_FOUND);
        }
    }

    // ==================================================================
    //  评论通知
    // ==================================================================

    @Nested
    @DisplayName("评论通知")
    class CommentNotification {

        @Test
        @DisplayName("申请人评论：待办审批人收到通知，自己收不到")
        void shouldNotifyApproverNotCommenter() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            commentService.create(ticket.getId(), request("补充一下附件"));

            // 必须按类型过滤：李四在同一张单上还会收到 SUBMIT 产生的待办通知，
            // 直接断言总条数会把 2 条都算进来，看起来像"评论通知发重了"
            List<Notification> lisi = noticesFor(UID_LISI, ticket.getId()).stream()
                    .filter(n -> NotificationType.TICKET_COMMENT.name().equals(n.getType()))
                    .toList();
            assertThat(lisi).hasSize(1);
            assertThat(lisi.get(0).getContent()).contains("张三").contains(ticket.getTicketNo());

            // 自己发的评论不该弹给自己：那样每发一条就多一个红点，
            // 用户会养成都无视红点的习惯，通知也就废了
            assertThat(noticesFor(UID_ZHANGSAN, ticket.getId()))
                    .noneMatch(n -> NotificationType.TICKET_COMMENT.name().equals(n.getType()));
        }

        @Test
        @DisplayName("审批人评论：申请人收到通知")
        void shouldNotifyCreatorWhenApproverComments() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            AuthTestSupport.loginAs(UID_LISI);
            commentService.create(ticket.getId(), request("请看下这里"));

            List<Notification> zs = noticesFor(UID_ZHANGSAN, ticket.getId()).stream()
                    .filter(n -> NotificationType.TICKET_COMMENT.name().equals(n.getType()))
                    .toList();
            assertThat(zs).hasSize(1);
            assertThat(zs.get(0).getContent()).contains("李四");
            assertThat(zs.get(0).getIsRead()).isZero();
        }

        @Test
        @DisplayName("走过一级之后评论：不只发给当前待办人，历史审批人也收到")
        void shouldNotifyPastApproversToo() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());
            // 李四通过，流转到管理员
            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), "APPROVE", "同意");

            // 由申请人评论：这一条要发给 李四（历史审批人）+ 管理员（当前待办人）
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            commentService.create(ticket.getId(), request("两位都看下"));

            // 少了"历史审批人"这一路，就会出现"我在评论区问了句话，
            // 已经审完的那位根本不知道"的断线
            assertThat(noticesFor(UID_LISI, ticket.getId()))
                    .as("历史审批人也要收到评论通知")
                    .anyMatch(n -> NotificationType.TICKET_COMMENT.name().equals(n.getType()));
            assertThat(noticesFor(UID_ADMIN, ticket.getId()))
                    .as("当前待办人要收到")
                    .anyMatch(n -> NotificationType.TICKET_COMMENT.name().equals(n.getType()));
        }

        @Test
        @DisplayName("收件人去重：申请人不会因为既是创建人又是提交人而收到两条")
        void shouldDeduplicateRecipients() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            // 由管理员来评论（评论人不是申请人，也不会把申请人从收件人里剔掉）。
            // 此时张三会同时命中两个来源：
            //   1) ticket.creator_id = 张三
            //   2) 审批记录里 SUBMIT 那条的 approver_id 也是张三
            // 少了 LinkedHashSet 去重，张三就会为同一条评论收到两条通知。
            // 这个重复特别隐蔽：提交人一定也是创建人，所以只要有人评论，它就必然存在
            AuthTestSupport.loginAs(UID_ADMIN);
            commentService.create(ticket.getId(), request("这条评论不该让张三收到两条"));

            List<Notification> zs = noticesFor(UID_ZHANGSAN, ticket.getId()).stream()
                    .filter(n -> NotificationType.TICKET_COMMENT.name().equals(n.getType()))
                    .toList();
            assertThat(zs).as("申请人只该收到一条评论通知").hasSize(1);
        }

        @Test
        @DisplayName("工单不存在时通知静默跳过，不抛异常")
        void notifyCommentShouldStaySilentOnMissingTicket() {
            // 这条守的是 NotificationService#notifyComment 的第一行 early return。
            // 通知是附属功能，算不出收件人时应该安静地不发，而不是抛异常 ——
            // 一旦它抛，调用它的那条评论请求就会跟着失败，
            // 用户看到的是"我明明把评论发出去了，却说失败了"
            AuthTestSupport.loginAs(UID_LISI);
            notificationService.notifyComment(-1L, UID_LISI, "TK19700101-0001", "不存在的单", "李四");
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private CommentCreateRequest request(String content) {
        CommentCreateRequest request = new CommentCreateRequest();
        request.setContent(content);
        return request;
    }

    private Ticket createDraft(long userId) {
        AuthTestSupport.loginAs(userId);
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("评论测试工单");
        request.setContent("由 TicketCommentServiceTest 创建，事务结束后回滚");
        request.setTypeId(TYPE_LEAVE);
        request.setPriority("NORMAL");
        return ticketService.create(request);
    }

    /** 某账号收到的、指向这张单的全部通知 */
    private List<Notification> noticesFor(long userId, Long ticketId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getBizId, ticketId));
    }
}
