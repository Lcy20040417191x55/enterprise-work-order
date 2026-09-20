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
 * 审批记录。只追加不修改，完整保留工单的流转轨迹。
 * 不继承 BaseEntity —— 该表没有逻辑删除和更新时间语义。
 */
@Data
@TableName("approval_record")
public class ApprovalRecord implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Integer step;

    private Long approverId;

    /** 操作人姓名快照，避免用户改名后历史记录失真 */
    private String approverName;

    /** 取值见 {@link com.enterprise.workorder.enums.ApprovalAction} */
    private String action;

    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}