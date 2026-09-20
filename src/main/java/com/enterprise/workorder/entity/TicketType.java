package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工单类型，决定该类工单走几级审批、每级由谁审。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ticket_type")
public class TicketType extends BaseEntity {

    private String code;

    private String name;

    private String description;

    /**
     * 审批链，逗号分隔的角色编码，例如 "DEPT_LEADER,ADMIN"。
     * 解析后得到审批总级数。
     */
    private String approvalFlow;

    private Integer sort;

    private Integer enabled;
}