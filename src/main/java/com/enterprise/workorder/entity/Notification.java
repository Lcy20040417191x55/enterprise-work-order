package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 站内通知。
 *
 * <p><b>为什么是"写扩散"而不是"读时计算"</b>：通知产生的那一刻就为每个接收人插入一行。
 * 另一种做法是用户打开通知中心时现场比对"哪些工单状态变了"，但那样无法回答
 * "我什么时候看过了" —— 已读状态必须落到某个持久化的行上。写扩散的代价是行数增长快，
 * 而通知是典型的冷热分明数据：超过一两周基本没人回看，可以按 created_at 归档清理。</p>
 *
 * <p>继承 {@link BaseEntity} 是为了拿到 deleted / created_at / updated_at：
 * 通知需要按时间倒序（created_at），也需要"用户把通知删掉但不真的删库"（deleted）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification")
public class Notification extends BaseEntity {

    /** 接收人ID。一条通知只属于一个人，群发场景由写扩散展开成多行 */
    private Long userId;

    /** 取值见 {@link com.enterprise.workorder.enums.NotificationType} */
    private String type;

    /** 列表里显示的标题，产生通知时就固化下来 */
    private String title;

    /**
     * 正文摘要。
     *
     * <p>存摘要而不是存模板参数，是为了让历史通知不随业务数据变化而"变脸"：
     * 工单标题后来改了，通知里仍显示当时的标题，用户不会看到一条指向
     * 一个自己没见过的标题的通知而怀疑点错了。</p>
     */
    private String content;

    /** 关联业务主键。目前都指向工单ID，点击通知即跳转工单详情 */
    private Long bizId;

    /** 0 未读 / 1 已读 */
    private Integer isRead;

    private LocalDateTime readAt;
}
