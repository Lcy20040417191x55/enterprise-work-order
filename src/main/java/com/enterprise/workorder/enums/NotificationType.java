package com.enterprise.workorder.enums;

/**
 * 通知类型。
 *
 * <p>取值同时决定了两件事：列表里显示什么图标/颜色，以及点击后跳到哪。
 * 目前四类都指向工单详情，若将来出现"系统公告"这类没有 biz_id 的通知，
 * 前端按 {@code bizId == null} 判断不可点击即可，不需要新增字段。</p>
 */
public enum NotificationType {

    /** 有新的工单提交，通知当前待办审批人 */
    TICKET_TODO("待办提醒"),

    /** 工单终审通过，通知申请人 */
    TICKET_APPROVED("审批通过"),

    /** 工单被驳回，通知申请人 */
    TICKET_REJECTED("审批驳回"),

    /** 工单有了新评论，通知除评论人之外的参与人 */
    TICKET_COMMENT("工单评论"),

    /**
     * 申请人催办：提醒当前待办人尽快处理。
     *
     * <p>与 {@link #TICKET_TODO} 分开而不是复用，差别在"语义"而非"送达对象"：
     * 待办提醒表示"新单子来了"，催办表示"同一张单，有人等不及了"。
     * 前端据此用不同的图标与颜色（催办是告急色），用户一眼能分辨任务的紧急程度；
     * 若复用同一个类型，一条催办通知和一条新待办在列表里长得一模一样，
     * 催办就失去了它全部的提醒价值。</p>
     */
    TICKET_URGE("催办提醒");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析：未知类型返回 null 而不是抛异常。
     *
     * <p>通知类型会随版本增加，旧版本前端遇到新类型不该整页崩掉。
     * 展示层拿到 null 时回落到原始字符串即可。</p>
     */
    public static NotificationType parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 展示名；未知类型原样返回，避免脏数据导致接口异常 */
    public static String labelOf(String value) {
        NotificationType type = parseOrNull(value);
        return type == null ? value : type.getLabel();
    }
}
