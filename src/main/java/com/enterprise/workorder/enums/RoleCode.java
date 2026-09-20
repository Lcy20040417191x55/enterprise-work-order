package com.enterprise.workorder.enums;

/**
 * 系统角色。用枚举而非魔法字符串，避免拼写错误导致的越权。
 */
public enum RoleCode {

    /** 普通员工：只能创建工单、查看自己的工单 */
    EMPLOYEE("普通员工"),

    /** 审批人：可审批本部门的工单 */
    APPROVER("审批人"),

    /** 管理员：可审批、可管理基础数据 */
    ADMIN("系统管理员");

    private final String label;

    RoleCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}