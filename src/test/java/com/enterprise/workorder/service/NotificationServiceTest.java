package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.dto.NotificationQuery;
import com.enterprise.workorder.dto.NotificationVO;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.entity.Notification;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.enums.ApprovalAction;
import com.enterprise.workorder.enums.NotificationType;
import com.enterprise.workorder.mapper.NotificationMapper;
import com.enterprise.workorder.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站内通知测试。
 *
 * <p><b>为什么断言"落在数据库里的通知行"，而不是"调用了 notifyXxx"</b>：
 * 通知的价值全在"谁在什么时候收到了什么"这一行记录上。若只断言方法被调用过，
 * 收件人算错（比如通知发给了申请人自己）、内容里少了驳回理由这类问题
 * 一个都测不出来 —— 而它们恰恰是通知功能最容易出错的两种。</p>
 *
 * <p><b>为什么必须连真实数据库</b>：通知读接口一律取 SecurityContext 里的登录人，
 * 而"取不到登录人"和"取到了别人的 id"这两种失败只有在真实执行路径上才会暴露。
 * Mock 掉 SecurityUtils 等于把被测的关键行为替换掉了。</p>
 *
 * <p>@Transactional 保证用例造的数据在结束后回滚，不污染开发库。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("站内通知")
class NotificationServiceTest {

    private static final Long UID_ADMIN = 1L;
    private static final Long UID_ZHANGSAN = 2L;
    private static final Long UID_LISI = 3L;
    private static final Long UID_WANGWU = 4L;

    /** 请假申请：审批链 DEPT_LEADER,ADMIN —— 技术部主管是李四(id=3) */
    private static final Long TYPE_LEAVE = 1L;
    /** IT报修：审批链 DEPT_LEADER —— 一级，便于测"终审通过" */
    private static final Long TYPE_IT_REPAIR = 3L;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationMapper notificationMapper;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    // ==================================================================
    //  提交 / 流转产生的待办通知
    // ==================================================================

    @Nested
    @DisplayName("待办通知")
    class TodoNotification {

        @Test
        @DisplayName("提交后审批人收到待办通知，正文含单号与提交人姓名")
        void shouldNotifyApproverOnSubmit() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            List<Notification> received = notificationsOf(UID_LISI, NotificationType.TICKET_TODO);

            // 只取与本单相关的，避免受库中历史数据影响
            List<Notification> mine = received.stream()
                    .filter(n -> ticket.getId().equals(n.getBizId()))
                    .toList();
            assertThat(mine).hasSize(1);
            Notification n = mine.get(0);
            assertThat(n.getTitle()).isEqualTo("有新的工单待您审批");
            assertThat(n.getContent())
                    .contains(ticket.getTicketNo())
                    .contains("张三")
                    .contains(ticket.getTitle());
            assertThat(n.getIsRead()).isZero();
            assertThat(n.getReadAt()).isNull();
        }

        @Test
        @DisplayName("申请人不会收到自己的待办通知")
        void shouldNotNotifyCreatorOnSubmit() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            // 待办是发给"要去审的人"的，发给申请人本人等于制造一条点进去
            // 发现自己不能审批的假提醒
            assertThat(notificationsOf(UID_ZHANGSAN, null)).noneMatch(
                    n -> ticket.getId().equals(n.getBizId()));
        }

        @Test
        @DisplayName("流转到下一级时，新的待办人收到通知")
        void shouldNotifyNextApproverOnForward() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            // 一级（李四）通过 -> 流转到二级（管理员）
            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            assertThat(notificationsOf(UID_ADMIN, NotificationType.TICKET_TODO))
                    .anyMatch(n -> ticket.getId().equals(n.getBizId()));
        }
    }

    // ==================================================================
    //  驳回 / 通过通知
    // ==================================================================

    @Nested
    @DisplayName("结果通知")
    class ResultNotification {

        @Test
        @DisplayName("驳回后申请人收到通知，且正文带驳回理由")
        void shouldNotifyCreatorOnReject() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.REJECT.name(), "预算不足，暂缓");

            List<Notification> mine = notificationsOf(UID_ZHANGSAN, NotificationType.TICKET_REJECTED)
                    .stream().filter(n -> ticket.getId().equals(n.getBizId())).toList();
            assertThat(mine).hasSize(1);
            // 正文必须带理由：申请人只看通知就知道要改什么，
            // 否则还得点回详情页翻审批轨迹，通知的提醒价值就没了
            assertThat(mine.get(0).getContent()).contains("预算不足，暂缓");
        }

        @Test
        @DisplayName("终审通过后申请人收到「已通过」通知")
        void shouldNotifyCreatorOnFinalApprove() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            // IT报修只有一级，李四通过即终审
            Ticket ticket = createAndSubmit(TYPE_IT_REPAIR);

            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "已修好");

            List<Notification> mine = notificationsOf(UID_ZHANGSAN, NotificationType.TICKET_APPROVED)
                    .stream().filter(n -> ticket.getId().equals(n.getBizId())).toList();
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).getContent()).contains(ticket.getTicketNo());
        }

        @Test
        @DisplayName("中间级通过时不发「已通过」通知，只有终审才发")
        void shouldNotSendApprovedBeforeFinalStep() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            AuthTestSupport.loginAs(UID_LISI);
            ticketService.approve(ticket.getId(), ApprovalAction.APPROVE.name(), "同意");

            // 后面还有一级没走完就告诉申请人"已通过"，会让他以为可以收尾了
            assertThat(notificationsOf(UID_ZHANGSAN, NotificationType.TICKET_APPROVED))
                    .noneMatch(n -> ticket.getId().equals(n.getBizId()));
        }
    }

    // ==================================================================
    //  读取与标记已读
    // ==================================================================

    @Nested
    @DisplayName("读取与标记已读")
    class ReadAndMark {

        @Test
        @DisplayName("分页查询只返回自己的通知")
        void shouldOnlyReturnOwnNotifications() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            // 换成王五（与本单无关）来看，不该看到发给李四的通知
            AuthTestSupport.loginAs(UID_WANGWU);
            NotificationQuery query = new NotificationQuery();
            List<NotificationVO> rows = notificationService.page(query).getRecords();

            // 断言到具体 bizId，而不是"列表为空"：开发库里可能已有历史通知，
            // 空列表断言会随环境时红时绿；而"过滤 userId 那行被删掉"这种越权回归，
            // 只有对着本单的 bizId 断言才抓得住
            assertThat(rows).noneMatch(vo -> ticket.getId().equals(vo.getBizId()));

            // 李四能看到属于本单的那条
            AuthTestSupport.loginAs(UID_LISI);
            assertThat(notificationService.page(query).getRecords())
                    .anyMatch(vo -> ticket.getId().equals(vo.getBizId()));
        }

        @Test
        @DisplayName("未读数只统计自己的未读")
        void unreadCountShouldBePerUser() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            createAndSubmit(TYPE_LEAVE);

            long lisiUnread = unreadAfterLoginAs(UID_LISI);
            assertThat(lisiUnread).isGreaterThanOrEqualTo(1);
            // 王五没有任何待办，未读数必须是 0
            assertThat(unreadAfterLoginAs(UID_WANGWU)).isZero();
        }

        @Test
        @DisplayName("标记已读后未读数减少，readAt 被填上")
        void shouldMarkReadAndStampTime() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            AuthTestSupport.loginAs(UID_LISI);
            long before = notificationService.unreadCount();
            Notification target = notificationsOf(UID_LISI, NotificationType.TICKET_TODO).stream()
                    .filter(n -> ticket.getId().equals(n.getBizId()))
                    .findFirst().orElseThrow();

            notificationService.markRead(target.getId());

            assertThat(notificationService.unreadCount()).isEqualTo(before - 1);
            Notification after = notificationMapper.selectById(target.getId());
            assertThat(after.getIsRead()).isEqualTo(1);
            assertThat(after.getReadAt()).isNotNull();
        }

        @Test
        @DisplayName("重复标记同一条不会重复扣减未读数")
        void shouldBeIdempotentOnRepeatedMarkRead() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            AuthTestSupport.loginAs(UID_LISI);
            Notification target = notificationsOf(UID_LISI, NotificationType.TICKET_TODO).stream()
                    .filter(n -> ticket.getId().equals(n.getBizId()))
                    .findFirst().orElseThrow();

            notificationService.markRead(target.getId());
            long once = notificationService.unreadCount();
            notificationService.markRead(target.getId());

            // 更新语句里带了 is_read = 0 条件，第二次匹配不到行，未读数因此不变。
            // 若去掉那个条件，第二次会把 read_at 刷新成更晚的时间 ——
            // "什么时候看的"这条信息就失真了
            assertThat(notificationService.unreadCount()).isEqualTo(once);
        }

        @Test
        @DisplayName("不能标记别人的通知为已读")
        void shouldNotMarkOthersNotification() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            Notification lisiNotice = notificationsOf(UID_LISI, NotificationType.TICKET_TODO).stream()
                    .filter(n -> ticket.getId().equals(n.getBizId()))
                    .findFirst().orElseThrow();

            // 换个账号来标记别人那条。这类越权不泄露数据、只破坏数据，
            // 权限评审时最容易被放过，所以必须有测试盯住
            AuthTestSupport.loginAs(UID_WANGWU);
            notificationService.markRead(lisiNotice.getId());

            Notification after = notificationMapper.selectById(lisiNotice.getId());
            assertThat(after.getIsRead()).isZero();
            assertThat(after.getReadAt()).isNull();
        }

        @Test
        @DisplayName("全部已读只影响自己的通知")
        void markAllReadShouldOnlyAffectSelf() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            Notification lisiNotice = notificationsOf(UID_LISI, NotificationType.TICKET_TODO).stream()
                    .filter(n -> ticket.getId().equals(n.getBizId()))
                    .findFirst().orElseThrow();

            AuthTestSupport.loginAs(UID_WANGWU);
            notificationService.markAllRead();

            assertThat(notificationMapper.selectById(lisiNotice.getId()).getIsRead()).isZero();
        }

        @Test
        @DisplayName("只看未读时，已读通知不出现在结果里")
        void unreadOnlyShouldFilterReadOnes() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_LEAVE);

            AuthTestSupport.loginAs(UID_LISI);
            Notification target = notificationsOf(UID_LISI, NotificationType.TICKET_TODO).stream()
                    .filter(n -> ticket.getId().equals(n.getBizId()))
                    .findFirst().orElseThrow();
            notificationService.markRead(target.getId());

            NotificationQuery query = new NotificationQuery();
            query.setUnreadOnly(true);
            // 默认 pageSize 只有 10，开发库里李四的通知可能不止 10 条，
            // 目标行会被挤出第一页导致断言假红。这里放大到上限 100
            query.setPageSize(100);
            // unreadOnly 默认值是 false，且入参是 Boolean 包装类型。
            // 前端不传这个参数时它是 null，若实现里写成 if (query.getUnreadOnly())
            // 就会 NPE —— 这里把 true 和默认两条路都走一遍
            assertThat(notificationService.page(query).getRecords())
                    .noneMatch(vo -> target.getId().equals(vo.getId()));

            NotificationQuery all = new NotificationQuery();
            all.setPageSize(100);
            assertThat(notificationService.page(all).getRecords())
                    .anyMatch(vo -> target.getId().equals(vo.getId()));
        }
    }

    // ==================================================================
    //  极端输入：通知不该反过来阻断主流程
    // ==================================================================

    @Nested
    @DisplayName("超长内容")
    class OversizedContent {

        @Test
        @DisplayName("驳回理由超过列宽时按列宽截断，而不是抛异常")
        void shouldTruncateOversizedReason() {
            AuthTestSupport.loginAs(UID_ZHANGSAN);
            Ticket ticket = createAndSubmit(TYPE_IT_REPAIR);

            // 接口层 @Size 限 500 字，正常路径传不进超长文本。
            // 但通知是附属功能，一旦它因列宽报错，整笔审批会一起失败 ——
            // 这条用例守的就是那道防线
            String hugeReason = "超".repeat(800);
            notificationService.notifyRejected(ticket.getId(), UID_ZHANGSAN,
                    ticket.getTicketNo(), ticket.getTitle(), hugeReason);

            List<Notification> mine = notificationsOf(UID_ZHANGSAN, NotificationType.TICKET_REJECTED)
                    .stream().filter(n -> ticket.getId().equals(n.getBizId())).toList();
            assertThat(mine).hasSize(1);
            // VARCHAR(500) 是 500 个字符（utf8mb4），不是 500 字节，
            // 所以这里按字符数断言
            assertThat(mine.get(0).getContent().length()).isLessThanOrEqualTo(500);
            assertThat(mine.get(0).getContent()).endsWith("…");
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private Ticket createAndSubmit(Long typeId) {
        TicketCreateRequest request = new TicketCreateRequest();
        request.setTitle("通知测试工单");
        request.setContent("由 NotificationServiceTest 创建，事务结束后回滚");
        request.setTypeId(typeId);
        request.setPriority("NORMAL");
        Ticket ticket = ticketService.create(request);
        ticketService.submit(ticket.getId());
        return ticket;
    }

    /** 以指定账号登录后查未读数。读接口不接受 userId 参数，只能这样测 */
    private long unreadAfterLoginAs(long userId) {
        AuthTestSupport.loginAs(userId);
        return notificationService.unreadCount();
    }

    /** type 传 null 表示不按类型过滤 */
    private List<Notification> notificationsOf(long userId, NotificationType type) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId);
        if (type != null) {
            wrapper.eq(Notification::getType, type.name());
        }
        return notificationMapper.selectList(wrapper);
    }
}
