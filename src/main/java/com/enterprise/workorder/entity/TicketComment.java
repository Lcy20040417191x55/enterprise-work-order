package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工单评论。
 *
 * <p><b>为什么不复用 approval_record</b>：审批记录代表"制度"，是流程动作的产物，
 * 只追加、不可修改、不可删除，任何一次编辑都会破坏审计价值；评论代表"沟通"，
 * 允许补一句、允许撤回自己刚发错的内容。两者混在一张表里，
 * 前者需要的不可变性就没法保证 —— 一句"这条评论删一下"就能顺手改掉审批意见。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_comment")
public class TicketComment extends BaseEntity {

    private Long ticketId;

    private Long userId;

    /**
     * 评论人姓名快照。
     *
     * <p>与 approval_record.approver_name 同理：用户改名后，
     * 历史评论应当显示当时的名字，而不是让三年前的一句话突然换上今天的新名字。</p>
     */
    private String userName;

    private String content;
}
