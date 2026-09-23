package com.enterprise.workorder.service;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.dto.AttachmentVO;
import com.enterprise.workorder.dto.CommentCreateRequest;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketOpLogVO;
import com.enterprise.workorder.dto.TicketUpdateRequest;
import com.enterprise.workorder.entity.Notification;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketOpLog;
import com.enterprise.workorder.enums.NotificationType;
import com.enterprise.workorder.enums.TicketOpAction;
import com.enterprise.workorder.mapper.NotificationMapper;
import com.enterprise.workorder.mapper.TicketOpLogMapper;
import com.enterprise.workorder.support.AuthTestSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单操作日志与催办的测试。
 *
 * <p><b>这一组用例守护的核心只有一句：凡是改动过工单的动作，都要在 ticlet_op_log 里留下一条。</b>
 * 这类"漏记"用别的手段几乎测不出来 —— 它不报错、不影响功能，
 * 只在几个月后有人想复盘时才表现为"这里明明做过什么，却查不到"。
 * 因此每个动作各有一条独立断言，而不是笼统地"总条数大于 0"：
 * 后者在漏记某一种动作时照样绿。</p>
 *
 * <p><b>另一个重点是催办的三重约束</b>（只有创建人、只有审批中、30 分钟限流）。
 * 前两条是权限，第三条是"限流"，而限流最容易写成"看起来生效、实际并发下失效" ——
 * 这里至少把串行场景下的边界（刚好 30 分钟该放行）钉住。</p>
 *
 * <p>连真实 MySQL，靠 @Transactional 回滚，不污染开发数据。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("工单操作日志与催办")
class TicketOpLogServiceTest {

    private static final long UID_ADMIN = 1L;
    private static final long UID_ZHANGSAN = 2L;
    private static final long UID_LISI = 3L;
    private static final long UID_WANGWU = 4L;

    /** 请假申请：DEPT_LEADER,ADMIN —— 两级 */
    private static final long TYPE_LEAVE = 1L;
    /** IT报修：DEPT_LEADER —— 一级，便于造出"已通过"的终态 */
    private static final long TYPE_IT_REPAIR = 3L;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketCommentService commentService;

    @Autowired
    private TicketAttachmentService attachmentService;

    @Autowired
    private TicketOpLogService opLogService;

    @Autowired
    private TicketOpLogMapper opLogMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    // ==================================================================
    //  日志覆盖：每个动作各一条
    // ==================================================================

    @Nested
    @DisplayName("动作留痕")
    class Recording {

        @Test
        @DisplayName("创建草稿就有一条 CREATE，操作人姓名是快照")
        void createShouldBeLogged() {
            Ticket ticket = createDraft(UID_ZHANGSAN);

            List<TicketOpLogVO> logs = ticketService.opLogs(ticket.getId());
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getAction()).isEqualTo(TicketOpAction.CREATE.name());
            assertThat(logs.get(0).getOperatorId()).isEqualTo(UID_ZHANGSAN);
            // 姓名是快照而不是实时 join：用户改名或离职后，历史日志仍要显示当时的名字
            assertThat(logs.get(0).getOperatorName()).isEqualTo("张三");
            assertThat(logs.get(0).getCreatedAt()).isNotNull();
            // actionLabel 由后端给出，前端不再维护第二份枚举映射
            assertThat(logs.get(0).getActionLabel()).isEqualTo("创建工单");
        }

        @Test
        @DisplayName("提交、审批、通过：审批链上的动作一条不少，且顺序就是发生顺序")
        void approvalFlowShouldBeFullyLogged() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), "APPROVE", "一级同意");

            AuthTestSupport.loginAs(UID_ADMIN);
            ticketService.approve(ticket.getId(), "APPROVE", "终审通过");

            assertThat(actionsOf(ticket.getId()))
                    .containsExactly(
                            TicketOpAction.CREATE.name(),
                            TicketOpAction.SUBMIT.name(),
                            TicketOpAction.APPROVE.name(),
                            TicketOpAction.APPROVE.name());
        }

        @Test
        @DisplayName("驳回写的是 REJECT，不是 APPROVE")
        void rejectShouldBeLoggedAsReject() {
            Ticket ticket = createDraft(UID_ZHANGSAN);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());

            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), "REJECT", "金额超预算");

            List<TicketOpLogVO> logs = ticketService.opLogs(ticket.getId());
            TicketOpLogVO last = logs.get(logs.size() - 1);
            // 若把驳回也记成 APPROVE，日志上就看不出这张单曾经被打回过，
            // 而"被驳回几次"恰恰是判断这张单顺不顺的关键信息
            assertThat(last.getAction()).isEqualTo(TicketOpAction.REJECT.name());
            assertThat(last.getDetail()).contains("金额超预算");
        }

        @Test
        @DisplayName("修改、撤回、作废、删除都会留痕")
        void applicantActionsShouldBeLogged() {
            // 修改
            Ticket draft = createDraft(UID_ZHANGSAN);
            ticketService.update(draft.getId(), updateRequest("改过的标题"));
            assertThat(actionsOf(draft.getId()))
                    .contains(TicketOpAction.UPDATE.name());

            // 撤回
            Ticket pending = submitAsZhangSan();
            ticketService.withdraw(pending.getId());
            assertThat(actionsOf(pending.getId()))
                    .contains(TicketOpAction.WITHDRAW.name());

            // 作废
            ticketService.cancel(pending.getId(), "不办了");
            assertThat(actionsOf(pending.getId()))
                    .contains(TicketOpAction.CANCEL.name());

            // 删除。改用一张新草稿：已经提交过的单删不掉（设计如此）
            Ticket toDelete = createDraft(UID_ZHANGSAN);
            ticketService.delete(toDelete.getId());

            // 这里刻意直接查 Mapper，而不是走 ticketService.opLogs ——
            // 因为工单已被逻辑删除，opLogs 的第一句 requireTicket 就会抛"工单不存在"，
            // 也就是说删除之后这张单的日志在接口层面已经读不到了，这是合理的：
            // 单据都消失了，谁还能在页面上看到它的动态。
            //
            // 但"日志行本身还在"这件事必须成立，它守护的是另一个场景：
            // 将来若加"回收站/误删恢复"，恢复回来的单子必须有完整的历史可读。
            // 日志表不带 deleted 字段、也不加外键，正是为了这一点 ——
            // 单据的消失不该连带历史一起消失。
            List<String> deletedActions = opLogMapper.selectList(
                            new LambdaQueryWrapper<TicketOpLog>()
                                    .eq(TicketOpLog::getTicketId, toDelete.getId()))
                    .stream().map(TicketOpLog::getAction).toList();
            assertThat(deletedActions).contains(TicketOpAction.CREATE.name(), TicketOpAction.DELETE.name());
        }

        @Test
        @DisplayName("评论与附件增删都要记进同一张表")
        void commentAndAttachmentShouldBeLogged() {
            Ticket ticket = submitAsZhangSan();

            CommentCreateRequest comment = new CommentCreateRequest();
            comment.setContent("我补一张发票，请看附件");
            commentService.create(ticket.getId(), comment);
            attachmentService.upload(ticket.getId(), pdf("发票.pdf"));

            List<TicketOpLogVO> logs = ticketService.opLogs(ticket.getId());
            assertThat(actionsOf(ticket.getId()))
                    .contains(TicketOpAction.COMMENT.name(), TicketOpAction.ATTACH_UPLOAD.name());

            // 明细也要有人读的样子：附件那一条必须带文件名，
            // 否则半年后只看到"上传了附件"，完全不知道传的是什么
            assertThat(logs.stream()
                    .filter(l -> TicketOpAction.ATTACH_UPLOAD.name().equals(l.getAction()))
                    .findFirst().orElseThrow().getDetail())
                    .isEqualTo("发票.pdf");

            // 撤回评论：评论行被逻辑删除，但日志里那条摘要还在
            Long commentId = commentService.list(ticket.getId()).get(0).getId();
            commentService.delete(ticket.getId(), commentId);
            AttachmentVO attachment = attachmentService.list(ticket.getId()).get(0);
            attachmentService.delete(ticket.getId(), attachment.getId());

            assertThat(actionsOf(ticket.getId()))
                    .contains(TicketOpAction.COMMENT_DELETE.name(), TicketOpAction.ATTACH_DELETE.name());
        }

        @Test
        @DisplayName("长评论在日志里被截断，但不会让评论本身失败")
        void longCommentShouldBeTruncatedInLog() {
            Ticket ticket = submitAsZhangSan();

            CommentCreateRequest comment = new CommentCreateRequest();
            // 1000 字是评论的上限，日志的 detail 列只有 500 字。
            // 不截断的话日志写入会失败，而失败发生在"评论已保存"之后 ——
            // 用户看到"发布失败"，评论却在库里，这类假象最难排查
            comment.setContent("长" + "内容".repeat(499));
            commentService.create(ticket.getId(), comment);

            String detail = logOf(ticket.getId(), TicketOpAction.COMMENT).getDetail();
            assertThat(detail.length()).isLessThanOrEqualTo(500);
            assertThat(detail).endsWith("…");
            // 评论本身完好无损
            assertThat(commentService.list(ticket.getId())).hasSize(1);
        }

        @Test
        @DisplayName("换行被折叠成空格：日志是时间线上的一行")
        void newlineShouldBeFolded() {
            Ticket ticket = submitAsZhangSan();

            CommentCreateRequest comment = new CommentCreateRequest();
            comment.setContent("第一行\n第二行\n\n第三行");
            commentService.create(ticket.getId(), comment);

            assertThat(logOf(ticket.getId(), TicketOpAction.COMMENT).getDetail())
                    .isEqualTo("第一行 第二行 第三行");
        }

        @Test
        @DisplayName("日志的可见性与工单详情完全一致：无关的人看不到")
        void unrelatedUserShouldNotReadLogs() {
            Ticket ticket = createDraft(UID_ZHANGSAN);

            // 王五既不是创建人、也不是这张单的待办人（技术部的单走李四）
            AuthTestSupport.loginAs(UID_WANGWU);
            assertThatThrownBy(() -> ticketService.opLogs(ticket.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权");
        }
    }

    // ==================================================================
    //  催办
    // ==================================================================

    @Nested
    @DisplayName("催办")
    class Urge {

        @Test
        @DisplayName("创建人催办：写一条日志，并给当前待办人推一条催办通知")
        void ownerShouldUrgeCurrentApprover() {
            Ticket ticket = submitAsZhangSan();

            ticketService.urge(ticket.getId(), "客户在等，麻烦今天看一下");

            TicketOpLogVO log = logOf(ticket.getId(), TicketOpAction.URGE);
            assertThat(log.getOperatorId()).isEqualTo(UID_ZHANGSAN);
            assertThat(log.getDetail()).contains("李四").contains("客户在等");

            // 收件人只有当前待办人。发给历史审批人只会制造噪音，
            // 而噪音多了的后果是有人干脆关掉通知，连带错过真正给他的待办
            List<Notification> notices = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getUserId, UID_LISI)
                            .eq(Notification::getBizId, ticket.getId())
                            .eq(Notification::getType, NotificationType.TICKET_URGE.name()));
            assertThat(notices).hasSize(1);
            assertThat(notices.get(0).getContent()).contains("张三").contains("客户在等");
            // 催办必须与待办提醒是不同的类型，否则在通知列表里长得一模一样，
            // "有人等不及了"这层意思就传达不出去
            assertThat(notices.get(0).getType()).isNotEqualTo(NotificationType.TICKET_TODO.name());
        }

        @Test
        @DisplayName("没有附言时也能催，通知正文不会出现空括号或残缺句子")
        void urgeWithoutReasonShouldBeFine() {
            Ticket ticket = submitAsZhangSan();

            ticketService.urge(ticket.getId(), null);

            Notification notice = notificationMapper.selectList(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getUserId, UID_LISI)
                            .eq(Notification::getBizId, ticket.getId())
                            .eq(Notification::getType, NotificationType.TICKET_URGE.name())).get(0);
            assertThat(notice.getContent()).doesNotContain("null").doesNotContain("：");
        }

        @Test
        @DisplayName("30 分钟内不能重复催：日志里已有上次时间")
        void urgeShouldBeRateLimited() {
            Ticket ticket = submitAsZhangSan();

            ticketService.urge(ticket.getId(), "第一次");
            assertThatThrownBy(() -> ticketService.urge(ticket.getId(), "第二次"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分钟");

            // 被拒绝的那一次不该留下日志，否则"催了几次"这个数字会虚高
            assertThat(actionsOf(ticket.getId()))
                    .filteredOn(TicketOpAction.URGE.name()::equals)
                    .hasSize(1);
        }

        @Test
        @DisplayName("刚好过了 30 分钟应当放行 —— 边界写错会让等足半小时的人被拒")
        void urgeShouldPassAtExactlyThirtyMinutes() {
            Ticket ticket = submitAsZhangSan();
            ticketService.urge(ticket.getId(), "第一次");

            // 把日志时间往前拨 30 分钟，模拟"刚好等够"的边界。
            // 限流条件写的是 minutes < 30，取等号必须放行：
            // 写成 <= 会让页面提示"已过 30 分钟"却仍然拒绝，看起来就是 bug
            backdateUrgeLog(ticket.getId(), 30);

            ticketService.urge(ticket.getId(), "半小时后再说一次");

            assertThat(actionsOf(ticket.getId()))
                    .filteredOn(TicketOpAction.URGE.name()::equals)
                    .hasSize(2);
        }

        @Test
        @DisplayName("不是创建人不能催（403）")
        void nonOwnerShouldNotUrge() {
            Ticket ticket = submitAsZhangSan();

            // 李四是当前待办人，也看不到"催办"的正当理由 —— 他可以直接审批
            AuthTestSupport.loginAs(UID_LISI);
            assertThatThrownBy(() -> ticketService.urge(ticket.getId(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能催办自己创建的工单");
        }

        @Test
        @DisplayName("管理员也不能替别人催：能看工单不等于能替申请人表态")
        void adminShouldNotUrgeOthersTicket() {
            Ticket ticket = submitAsZhangSan();

            AuthTestSupport.loginAs(UID_ADMIN);
            assertThatThrownBy(() -> ticketService.urge(ticket.getId(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能催办自己创建的工单");
        }

        @Test
        @DisplayName("草稿不能催：还没交出去，没人对它负有处理义务")
        void draftShouldNotBeUrged() {
            Ticket ticket = createDraft(UID_ZHANGSAN);

            assertThatThrownBy(() -> ticketService.urge(ticket.getId(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("审批中");
        }

        @Test
        @DisplayName("已办结的单不能催")
        void approvedShouldNotBeUrged() {
            // IT报修只有一级，李四通过后即办结
            Ticket ticket = createDraft(UID_ZHANGSAN, TYPE_IT_REPAIR);
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            ticketService.submit(ticket.getId());
            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), "APPROVE", "修好了");

            AuthTestSupport.loginAs(UID_ZHANGSAN);
            assertThatThrownBy(() -> ticketService.urge(ticket.getId(), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("审批中");
        }

        @Test
        @DisplayName("VO 上的 urgeable 标记只跟状态走，与「是不是我建的单」无关")
        void urgeableFlagShouldFollowStatusOnly() {
            Ticket ticket = submitAsZhangSan();

            // 创建人：状态允许 + 权限也够
            assertThat(ticketService.detail(ticket.getId()).getUrgeable()).isTrue();

            // 换成待办人来看：状态仍然允许（所以前端还有可能显示按钮），
            // 但"能不能催"要配 isOwner 一起判断，真正的拦截在后端。
            // 这里断言的是"标记本身不该被权限污染" —— 一旦混进去，
            // 同一个人在不同人眼里看到的同一张单会给出互相矛盾的状态说明
            AuthTestSupport.loginAs(UID_LISI);
            assertThat(ticketService.detail(ticket.getId()).getUrgeable()).isTrue();

            // 草稿：状态就不允许
            Ticket draft = createDraft(UID_ZHANGSAN);
            assertThat(ticketService.detail(draft.getId()).getUrgeable()).isFalse();
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private List<String> actionsOf(Long ticketId) {
        return ticketService.opLogs(ticketId).stream().map(TicketOpLogVO::getAction).toList();
    }

    private TicketOpLogVO logOf(Long ticketId, TicketOpAction action) {
        return ticketService.opLogs(ticketId).stream()
                .filter(l -> action.name().equals(l.getAction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("日志里没有 " + action + " 这条记录"));
    }

    /**
     * 把这张单的催办日志时间往前拨指定分钟数。
     *
     * <p>直接用 SQL 改而不是"等 30 分钟"：限流的边界只能在时间轴上造出来，
     * 而任何真实等待都会让这组测试变得没法跑。</p>
     */
    private void backdateUrgeLog(Long ticketId, int minutesAgo) {
        int updated = opLogMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.enterprise.workorder.entity.TicketOpLog>()
                        .setSql("created_at = DATE_SUB(NOW(), INTERVAL " + minutesAgo + " MINUTE)")
                        .eq("ticket_id", ticketId)
                        .eq("action", TicketOpAction.URGE.name()));
        assertThat(updated).as("必须真的改到那条催办日志").isEqualTo(1);
    }

    private Ticket createDraft(long userId) {
        return createDraft(userId, TYPE_LEAVE);
    }

    private Ticket createDraft(long userId, long typeId) {
        AuthTestSupport.loginAs(userId);
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("操作日志测试工单");
        request.setContent("由 TicketOpLogServiceTest 创建，事务结束后回滚");
        request.setTypeId(typeId);
        request.setPriority("NORMAL");
        return ticketService.create(request);
    }

    /** 造一张处于"审批中"的单：张三创建并提交，当前待办人是李四 */
    private Ticket submitAsZhangSan() {
        Ticket ticket = createDraft(UID_ZHANGSAN);
        AuthTestSupport.loginAs(UID_ZHANGSAN);
        ticketService.submit(ticket.getId());
        return ticket;
    }

    private TicketUpdateRequest updateRequest(String title) {
        TicketUpdateRequest request = new TicketUpdateRequest();
        request.setTitle(title);
        request.setTypeId(TYPE_LEAVE);
        return request;
    }

    private MultipartFile pdf(String fileName) {
        return new MockMultipartFile("file", fileName, "application/pdf",
                "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8));
    }
}