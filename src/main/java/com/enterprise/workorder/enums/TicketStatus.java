package com.enterprise.workorder.enums;

/**
 * 工单状态机。
 *
 * <pre>
 * DRAFT --提交--> PENDING --最后一级通过--> APPROVED
 *   |               |  |
 *   |               |  +--非最后一级通过--> PENDING (step+1)
 *   |               |  +--驳回--> REJECTED --修改后重新提交--> PENDING
 *   |               +--撤回--> WITHDRAWN --修改后重新提交--> PENDING
 *   |
 *   +--删除--> 逻辑删除（列表、详情、轨迹均不可见）
 *   |
 *   +--作废--> CLOSED     REJECTED --作废--> CLOSED     WITHDRAWN --作废--> CLOSED
 * </pre>
 *
 * <p><b>为什么"能不能改、能不能删、能不能作废、能不能提交"也放在枚举里</b>：
 * 这些规则只由状态决定。若散落在 Service 各处，迟早在某个入口写漏一条，
 * 典型表现是"这个接口允许改被驳回的单、那个接口却不允许"。
 * 集中到枚举后，判断只剩一句 {@code status.isEditable()}，不会写漏。</p>
 *
 * <p><b>为什么用 switch 表达式而不是 if 链</b>：
 * switch 要求覆盖全部枚举常量，以后新增状态时编译器会直接报错，
 * 逼着你把新状态的规则一次补全；if 链则会静默落进 else，把新状态当老状态处理。</p>
 *
 * <p><b>本类只回答"能不能做"，不负责"怎么做"</b>：
 * 写库与留痕仍然只允许出现在 TicketServiceImpl，避免状态规则被绕开。</p>
 */
public enum TicketStatus {

    DRAFT("草稿"),
    PENDING("审批中"),
    APPROVED("已通过"),
    REJECTED("已驳回"),
    WITHDRAWN("已撤回"),
    CLOSED("已作废");

    private final String label;

    TicketStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 内容是否可修改。
     *
     * <p>草稿、已驳回、已撤回三种状态可改：前两者尚未进入流程或已被打回，
     * 后者是申请人自己撤回来的，本意就是"我要改改再交"。
     * 审批中的单绝不能改 —— 否则审批人正在看的内容，可能在你点"通过"的那一刻被改掉，
     * 审批意见与审批对象对不上，责任无法界定。</p>
     *
     * <p><b>已撤回为什么必须可改</b>：撤回若不可改也不可重新提交，这个状态就成了死胡同 ——
     * 单据永远卡在那里，申请人只能另建一张新单，历史记录白白断掉。</p>
     */
    public boolean isEditable() {
        return switch (this) {
            case DRAFT, REJECTED, WITHDRAWN -> true;
            case PENDING, APPROVED, CLOSED -> false;
        };
    }

    /**
     * 是否可删除。
     *
     * <p>只有从未提交过的草稿能删。一旦进过审批流就必须保留记录 ——
     * 这是审计要求：审批记录表是只追加的，若工单可被物理抹去，
     * 那些 approval_record 就成了无主数据。</p>
     */
    public boolean isDeletable() {
        return switch (this) {
            case DRAFT -> true;
            case PENDING, APPROVED, REJECTED, WITHDRAWN, CLOSED -> false;
        };
    }

    /**
     * 是否可作废。
     *
     * <p>与"撤回"的分工：撤回针对审批中的单，撤回后单据还在、可以改完重新提交；
     * 作废是不可逆的终态，表示这件事不办了。二者刻意不重叠 ——
     * PENDING 只能撤回，其余"搁置态"只能作废，不需要判断"哪个优先"。</p>
     */
    public boolean isCancelable() {
        return switch (this) {
            case DRAFT, REJECTED, WITHDRAWN -> true;
            case PENDING, APPROVED, CLOSED -> false;
        };
    }

    /**
     * 是否可提交进入审批流。
     *
     * <p>与 {@link #isEditable()} 取同一组状态，但语义不同，因此分开写：
     * "能改"是给修改接口用的，"能交"是给提交接口用的。
     * 将来若出现"可改但不可交"的状态（例如被管理员冻结的草稿），
     * 只需改这一处，不会连带影响修改接口。</p>
     */
    public boolean isSubmittable() {
        return switch (this) {
            case DRAFT, REJECTED, WITHDRAWN -> true;
            case PENDING, APPROVED, CLOSED -> false;
        };
    }

    /**
     * 是否可审批。
     *
     * <p>只有审批中的单可审。审批人身份（是不是当前待办人）不在这里判断 ——
     * 那是"谁"的问题，本枚举只回答"在什么状态下"。</p>
     */
    public boolean isApprovable() {
        return switch (this) {
            case PENDING -> true;
            case DRAFT, APPROVED, REJECTED, WITHDRAWN, CLOSED -> false;
        };
    }

    /**
     * 是否允许增删附件。
     *
     * <p><b>为什么办结后必须冻结附件</b>：附件是"审批时看到的材料"的凭证。
     * APPROVED 之后若还能往单子里塞文件或删文件，事后复盘时"审批人当时依据的是哪份材料"
     * 就无从确认了 —— 争议往往几个月后才出现，而附件目录已经被人悄悄改过。
     * 审批中（PENDING）反而必须允许增删：审批人需要申请人补一张发票扫描件，
     * 这是最常见的沟通内容，冻结它就等于逼着双方走线下邮件。</p>
     *
     * <p>与 {@link #isEditable} 的区别值得注意：REJECTED 单据的内容可改、
     * 附件也可增删（补材料再提交）；但 PENDING 单据内容不可改、附件却可以。
     * 两者不是同一组状态，这正是不能复用 isEditable 的原因。</p>
     */
    public boolean isAttachable() {
        return switch (this) {
            case DRAFT, PENDING, REJECTED, WITHDRAWN -> true;
            case APPROVED, CLOSED -> false;
        };
    }

    /**
     * 是否可撤回。
     *
     * <p>只有审批中的单需要撤回 —— 都还没交出去，或者已经办结，撤回无从谈起。</p>
     */
    public boolean isWithdrawable() {
        return switch (this) {
            case PENDING -> true;
            case DRAFT, APPROVED, REJECTED, WITHDRAWN, CLOSED -> false;
        };
    }
}