package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工单（聚合根）。状态流转规则见 {@link com.enterprise.workorder.enums.TicketStatus}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket")
public class Ticket extends BaseEntity {

    private String ticketNo;

    private String title;

    /**
     * 工单内容。允许为空，且"空"是有效业务值（用户把内容删光了）。
     * 因此必须标 updateStrategy = ALWAYS —— 默认的 NOT_NULL 策略会把
     * {@code setContent(null)} 从 UPDATE 语句里剔除，表现为"改了但没生效"。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    private Long typeId;

    private String priority;

    /** 取值见 TicketStatus */
    private String status;

    private Long creatorId;

    private Long departmentId;

    /** 当前审批级次，从 1 开始；0 表示尚未提交 */
    private Integer currentStep;

    /** 审批总级次，由 ticket_type.approval_flow 解析得到 */
    private Integer totalStep;

    /**
     * 当前待审批人，决定谁的待办列表里能看到这张单。
     *
     * <p><b>必须标注 updateStrategy = ALWAYS。</b>MyBatis-Plus 默认策略是 NOT_NULL，
     * 即实体字段为 null 时该字段不会出现在 UPDATE 的 SET 子句中。而终审通过与驳回
     * 都需要把 currentApproverId 置回 null 表示"已无待办人"，若依赖默认策略，
     * 这个 null 会被静默丢弃，数据库里残留旧的审批人ID。</p>
     *
     * <p>残留的后果不只是数据不干净：applyScope 的 "all" 分支会按
     * current_approver_id 判定可见性，导致已办结的工单持续出现在原审批人的视野里。</p>
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long currentApproverId;

    private LocalDateTime submittedAt;

    /** 办结时间。与 currentApproverId 同理，撤回/重新提交时需要从有值改回 null */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}