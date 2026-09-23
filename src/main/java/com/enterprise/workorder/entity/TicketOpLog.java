package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单操作日志。只追加不修改，完整记录所有改动过这张单的动作。
 *
 * <p><b>与 {@link ApprovalRecord} 的分工</b>：那条记录是审批链的权威数据（带 step 语义，
 * 参与可见性判定与级次计算），本表是给人读的历史。审批动作两边都会写 ——
 * 那是刻意的冗余，一份供机器判定，一份供人复盘。详见 sql/migration-20260921-05.sql 的说明。</p>
 *
 * <p><b>为什么不继承 BaseEntity</b>：与 {@link ApprovalRecord} 同理，
 * 该表没有逻辑删除和更新时间语义。审计记录一旦可删可改，就不再是审计记录了。</p>
 */
@Data
@TableName("ticket_op_log")
public class TicketOpLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    /**
     * 操作人ID。为 null 表示"系统动作"（例如将来的超期自动关闭任务）。
     *
     * <p>刻意不用 0 或 -1 充当系统用户：那是在伪造一个不存在的账号，
     * 一旦有人按 operator_id 关联用户表，就会查出个空壳或者查不到，
     * 而 NULL 的语义在 SQL 里本来就是"没有值"，最诚实。</p>
     */
    private Long operatorId;

    /** 操作人姓名快照，避免用户改名后历史记录失真 */
    private String operatorName;

    /** 取值见 {@link com.enterprise.workorder.enums.TicketOpAction} */
    private String action;

    /** 给人看的补充说明，如附件名、评论摘要、催办对象。不参与任何计算 */
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}