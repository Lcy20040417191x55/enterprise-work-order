package com.enterprise.workorder.enums;

/**
 * 工单操作类型，用于操作日志（ticket_op_log）。
 *
 * <p><b>为什么这个枚举与 {@link ApprovalAction} 并存而不是复用它</b>：
 * ApprovalAction 只覆盖"审批链上的动作"，而操作日志要覆盖<b>所有</b>改动过这张单的动作 ——
 * 包括评论、附件增删、催办这些与审批流无关的事。
 * 若强行复用，就得往 ApprovalAction 里加 {@code COMMENT}、{@code ATTACH_UPLOAD}，
 * 而这些常量出现在审批流代码里会产生真正的歧义：审批链的解析、级次推进、
 * 可见性判定都在遍历这个枚举，多出来的值会被当成"合法的审批动作"处理。
 * 两个枚举各自服务一个用途，边界是清的。</p>
 *
 * <p><b>为什么展示名放在枚举里而不是前端映射表</b>：日志的文案要与落库的动作一一对应，
 * 放在枚举里改一处即可；前端只在需要"选图标"时再映射一次，
 * 而图标是纯展示，缺一个也只是回落成默认图标，不会产生错误信息。</p>
 */
public enum TicketOpAction {

    CREATE("创建工单"),
    UPDATE("修改内容"),
    SUBMIT("提交审批"),
    APPROVE("审批通过"),
    REJECT("审批驳回"),
    WITHDRAW("撤回工单"),
    CANCEL("作废工单"),
    DELETE("删除草稿"),
    COMMENT("发表评论"),
    COMMENT_DELETE("撤回评论"),
    ATTACH_UPLOAD("上传附件"),
    ATTACH_DELETE("删除附件"),
    URGE("催办");

    private final String label;

    TicketOpAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析：未知类型返回 null 而不是抛异常。
     *
     * <p>日志会随版本增加动作类型，旧版本前端遇到新类型不该整页崩掉；
     * 脏数据（例如人工改库写了个拼错的动作）同理。
     * 展示层拿到 null 时回落到原始字符串即可，与
     * {@link NotificationType#parseOrNull} 的处理方式一致。</p>
     */
    public static TicketOpAction parseOrNull(String value) {
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
        TicketOpAction action = parseOrNull(value);
        return action == null ? value : action.getLabel();
    }
}