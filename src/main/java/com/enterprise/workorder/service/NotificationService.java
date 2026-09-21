package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.dto.NotificationQuery;
import com.enterprise.workorder.dto.NotificationVO;
import com.enterprise.workorder.enums.NotificationType;

/**
 * 站内通知服务。
 *
 * <p><b>本接口的方法分两类，调用方要分清</b>：</p>
 * <ul>
 *   <li>{@code notifyXxx} 是给工单状态流转调用的<b>内部方法</b>，不带 @Transactional，
 *       依赖调用方事务：工单状态改了、通知没写成功，这种不一致比"通知多了一条"更难查，
 *       所以通知必须与状态变更同事务提交或回滚。</li>
 *   <li>{@code page / markRead / ...} 是面向前端的<b>读取与操作接口</b>，
 *       每次都只操作当前登录人自己的数据，不会碰到别人的通知。</li>
 * </ul>
 */
public interface NotificationService {

    // ================= 供工单流转调用的写入方法 =================

    /**
     * 通知当前待办审批人"有单子要审"。
     *
     * <p>提交、以及逐级流转到下一级时都会调用。两处的文案差异通过 {@code reason}
     * 传入而不是拆成两个方法：收件人、落库字段、索引都完全相同，
     * 拆两个方法只会让"未读数统计要不要一起改"这类问题反复出现。</p>
     *
     * <p>重新提交会再次通知同一审批人，这是刻意的：申请人改完内容说"你再看看"，
     * 审批人理应重新收到提醒，而不是靠自己去列表里碰运气刷新。</p>
     *
     * @param reason 事由短语，会拼进正文，例如"由张三提交"或"已通过上一级审批"
     */
    void notifyTodo(Long ticketId, Long approverId, String ticketNo, String title, String reason);

    /** 终审通过时通知申请人 */
    void notifyApproved(Long ticketId, Long creatorId, String ticketNo, String title);

    /** 驳回时通知申请人，并把驳回理由带进正文摘要 */
    void notifyRejected(Long ticketId, Long creatorId, String ticketNo, String title, String reason);

    /** 新评论时通知工单参与人（创建人 + 当前待办人 + 历史审批人），评论人自己除外 */
    void notifyComment(Long ticketId, Long commenterId, String ticketNo, String title, String commenterName);

    // ================= 面向当前登录人的读写 =================

    /** 分页查询当前登录人的通知，按时间倒序 */
    IPage<NotificationVO> page(NotificationQuery query);

    /** 当前登录人的未读数，用于顶栏角标 */
    long unreadCount();

    /** 标记单条已读。只能标自己的，别人的通知会因查不到而静默成功 */
    void markRead(Long id);

    /** 全部标记已读，返回本次实际影响的行数 */
    int markAllRead();
}
