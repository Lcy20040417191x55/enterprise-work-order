package com.enterprise.workorder.service;

import com.enterprise.workorder.enums.TicketOpAction;

import java.time.LocalDateTime;

/**
 * 工单操作日志记录器。
 *
 * <p><b>为什么这是一个独立组件，而不是让各处直接调 Mapper</b>：
 * 写一条日志要做三件事 —— 取当前操作人的姓名快照、处理"没有操作人"的系统动作、
 * 按列宽截断 detail。这三件事若散在工单、评论、附件三个 Service 里各写一遍，
 * 迟早出现"评论的日志有姓名、附件的日志是空的"这种不一致，
 * 而审计数据的不一致比功能缺失更难发现。</p>
 *
 * <p><b>{@link #record} 不带 @Transactional，是刻意的</b>：它由业务方法在自己的事务里调用，
 * 必须与业务动作同生共死。这一点与 {@code NotificationService} 的写入方法一致，
 * 但两者的"失败策略"不同，值得对照：
 * <ul>
 *   <li><b>通知失败可以被吞掉</b>（评论服务里就是这么做的）—— 少一条提醒，
 *       用户顶多是没收到消息，事实本身没有损失。</li>
 *   <li><b>日志失败不能被吞掉</b> —— 审计的核心要求就是"发生过的一定留得下"。
 *       一条静默消失的审批记录，等于那段历史永久缺失，而且没有任何迹象提示它曾经存在。
 *       因此这里让异常上抛，宁可整个业务动作失败，也不留一段无记录的历史。</li>
 * </ul>
 */
public interface TicketOpLogService {

    /**
     * 记录一次操作。
     *
     * <p>操作人取自当前登录态，不需要调用方传 —— 让调用方传 Long operatorId 的话，
     * 任何一处传错都会变成"日志把 A 的动作记到了 B 头上"，
     * 而这类错误在页面上看起来完全正常，只有对比原始数据才能发现。</p>
     *
     * <p>当前没有登录人时（例如将来由定时任务执行的系统动作），
     * 记为 operatorId = null、operatorName = "系统"，而不是编造一个用户。</p>
     *
     * @param detail 给人看的补充说明，如附件名、评论摘要；会按列宽截断，可以为 null
     */
    void record(Long ticketId, TicketOpAction action, String detail);

    /**
     * 这张单最近一次催办的时间，没有催办过时返回 null。
     *
     * <p>催办的频率限制依赖它。放在本接口而不是 {@code TicketService} 里，
     * 是因为催办的时间点本来就存在日志表里 —— 为它单独加一个字段或另一张表，
     * 等于把同一件事记两遍，而两份记录迟早会不一致。</p>
     */
    LocalDateTime lastUrgeAt(Long ticketId);
}