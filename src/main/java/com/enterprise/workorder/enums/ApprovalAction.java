package com.enterprise.workorder.enums;

/**
 * 审批动作。approval_record 表只追加不修改，每次流转都留痕。
 */
public enum ApprovalAction {

    SUBMIT("提交"),
    APPROVE("通过"),
    REJECT("驳回"),
    WITHDRAW("撤回"),
    CANCEL("作废");

    private final String label;

    ApprovalAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}