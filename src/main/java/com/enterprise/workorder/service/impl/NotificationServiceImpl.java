package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.workorder.dto.NotificationQuery;
import com.enterprise.workorder.dto.NotificationVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Notification;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.enums.NotificationType;
import com.enterprise.workorder.mapper.ApprovalRecordMapper;
import com.enterprise.workorder.mapper.NotificationMapper;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站内通知实现。
 *
 * <p><b>核心实现约定</b></p>
 * <ol>
 *   <li>写入方法不标 {@code @Transactional}，故意如此：它们由工单状态流转方法在
 *       自己的事务里调用，必须与状态变更同生共死。若在这里标 REQUIRES_NEW，
 *       一旦工单状态回滚而通知已提交，用户就会收到"工单被驳回了"却查不到该状态的通知。</li>
 *   <li>查询方法一律以 {@code SecurityUtils.getUserId()} 为准，不接受调用方传 userId ——
 *       参数化的 userId 只要有一处忘了校验，就是"能看别人通知"的越权漏洞。</li>
 *   <li>字段长度按数据库列宽截断，不让一条超长标题把整个业务事务拖垮。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** 与 notification.title / content 列宽保持一致 */
    private static final int TITLE_MAX = 128;
    private static final int CONTENT_MAX = 500;

    private final NotificationMapper notificationMapper;
    private final TicketMapper ticketMapper;
    private final ApprovalRecordMapper approvalRecordMapper;

    // ==================================================================
    //  写入：由工单流转调用
    // ==================================================================

    @Override
    public void notifyTodo(Long ticketId, Long approverId, String ticketNo,
                           String title, String reason) {
        if (approverId == null) {
            // 审批链解析不到人时提交本身就会失败，这里只做兜底防御
            log.warn("工单 {} 没有待办人，跳过待办通知", ticketNo);
            return;
        }
        insert(approverId, NotificationType.TICKET_TODO, ticketId,
                "有新的工单待您审批",
                String.format("工单「%s」（%s）%s，请及时处理", title, ticketNo, reason));
    }

    @Override
    public void notifyApproved(Long ticketId, Long creatorId, String ticketNo, String title) {
        insert(creatorId, NotificationType.TICKET_APPROVED, ticketId,
                "您的工单已审批通过",
                String.format("工单「%s」（%s）已全部审批通过", title, ticketNo));
    }

    @Override
    public void notifyRejected(Long ticketId, Long creatorId, String ticketNo,
                               String title, String reason) {
        String summary = reason == null
                ? String.format("工单「%s」（%s）被驳回，请修改后重新提交", title, ticketNo)
                : String.format("工单「%s」（%s）被驳回：%s", title, ticketNo, reason);
        insert(creatorId, NotificationType.TICKET_REJECTED, ticketId,
                "您的工单被驳回", summary);
    }

    /**
     * 新评论通知。
     *
     * <p><b>收件人怎么定</b>：创建人 + 当前待办人 + 所有审批过这张单的人，
     * 去掉评论人自己。评论是对话，只有把在流程里留下过痕迹的人都拉进来，
     * 才不会出现"我在评论区问了句话，审批人根本不知道"的断线。</p>
     *
     * <p>用 {@link LinkedHashSet} 去重：创建人很可能同时也是审批人（虽然自审会被
     * 审批链规避，但历史数据里可能存在），重复插入会让同一条评论收到两条通知。</p>
     */
    @Override
    public void notifyComment(Long ticketId, Long commenterId, String ticketNo,
                              String title, String commenterName) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            return;
        }

        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(ticket.getCreatorId());
        recipients.add(ticket.getCurrentApproverId());

        List<ApprovalRecord> records = approvalRecordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>()
                        .eq(ApprovalRecord::getTicketId, ticketId));
        for (ApprovalRecord record : records) {
            recipients.add(record.getApproverId());
        }

        recipients.remove(null);
        recipients.remove(commenterId);
        if (recipients.isEmpty()) {
            return;
        }

        String content = String.format("%s 在工单「%s」（%s）下发表了评论",
                commenterName, title, ticketNo);
        for (Long userId : recipients) {
            insert(userId, NotificationType.TICKET_COMMENT, ticketId, "工单有新评论", content);
        }
    }

    /**
     * 插入一条通知。
     *
     * <p>注意 {@code isRead} 与 {@code bizId} 都显式赋值：前者避免依赖数据库默认值
     * （MyBatis-Plus 的 INSERT 只会带上非 null 字段，默认值只有建表语句里那句才生效，
     * 若哪天列默认值被改掉，未读判断会静默失效）；后者为 null 时表示通知不可点击。</p>
     */
    private void insert(Long userId, NotificationType type, Long bizId,
                        String title, String content) {
        if (userId == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type.name());
        notification.setBizId(bizId);
        notification.setTitle(truncate(title, TITLE_MAX));
        notification.setContent(truncate(content, CONTENT_MAX));
        notification.setIsRead(0);
        notificationMapper.insert(notification);
    }

    /**
     * 按字符数截断。
     *
     * <p>数据库列宽是字符而不是字节（utf8mb4 下 VARCHAR(128) 就是 128 个字符），
     * 所以直接按 {@code String.length()} 截断即可，不需要按字节处理。
     * 目的不是"展示好看"，而是防止一个 500 字的驳回理由让审批事务报
     * "Data too long for column" 从而整单审批失败 —— 通知是附属功能，
     * 绝不能反过来阻断主流程。</p>
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ==================================================================
    //  读取与操作：面向当前登录人
    // ==================================================================

    @Override
    public IPage<NotificationVO> page(NotificationQuery query) {
        Long userId = SecurityUtils.getUserId();

        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId);
        // 显式写 Boolean.TRUE.equals，而不是 if (query.getUnreadOnly())：
        // 后者在参数缺省为 null 时会抛 NPE，布尔字段的三态是很容易踩的坑。
        if (Boolean.TRUE.equals(query.getUnreadOnly())) {
            wrapper.eq(Notification::getIsRead, 0);
        }
        // 按 id 倒序而不是 created_at 倒序：同一批写扩散插入的通知 created_at 可能
        // 完全相同（同一秒），靠时间排序结果不稳定，会出现刷新一次顺序变一次。
        // id 自增，天然等于插入顺序。
        wrapper.orderByDesc(Notification::getId);

        IPage<Notification> result = notificationMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        long total = result.getTotal();
        List<NotificationVO> rows = result.getRecords().stream().map(this::toVO).toList();
        Page<NotificationVO> voPage = new Page<>(result.getCurrent(), result.getSize(), total);
        voPage.setRecords(rows);
        return voPage;
    }

    @Override
    public long unreadCount() {
        Long userId = SecurityUtils.getUserId();
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
        return count == null ? 0L : count;
    }

    /**
     * 标记单条已读。
     *
     * <p><b>为什么查询条件里必须带 userId</b>：只按 id 更新的话，任何人拿到一个
     * 自增 id 就能把别人的通知标成已读。这类"看起来无害"的越权最容易漏 ——
     * 它不泄露数据，只破坏数据，所以没有明显的攻击收益，但也正因如此，
     * 权限评审时最容易被放过去。</p>
     *
     * <p>查不到（不是自己的 / 不存在）时静默返回：标记已读是幂等操作，
     * 重复点击或别人已删除该通知都不该报错给用户。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long id) {
        if (id == null) {
            return;
        }
        Long userId = SecurityUtils.getUserId();
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        Long userId = SecurityUtils.getUserId();
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1)
                .set(Notification::getReadAt, LocalDateTime.now()));
    }

    private NotificationVO toVO(Notification notification) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setTypeLabel(NotificationType.labelOf(notification.getType()));
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setBizId(notification.getBizId());
        // 数据库存的是 0/1，前端更愿意用布尔值；这里转换一次，前端就不用写 === 1
        vo.setRead(Integer.valueOf(1).equals(notification.getIsRead()));
        vo.setReadAt(notification.getReadAt());
        vo.setCreatedAt(notification.getCreatedAt());
        return vo;
    }
}
