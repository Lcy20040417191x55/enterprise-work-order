package com.enterprise.workorder.enums;

/**
 * 工单优先级。
 */
public enum Priority {

    LOW("低"),
    NORMAL("普通"),
    HIGH("高"),
    URGENT("紧急");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}