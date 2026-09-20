package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单列表/详情展示对象。
 * 不直接返回实体，避免把 password 这类敏感字段、以及需要二次查询才能显示的名字丢给前端。
 */
@Data
public class TicketVO {

    private Long id;

    private String ticketNo;

    private String title;

    private String content;

    private Long typeId;

    private String typeName;

    private String priority;

    private String priorityLabel;

    private String status;

    private String statusLabel;

    private Long creatorId;

    private String creatorName;

    private Long departmentId;

    private String departmentName;

    private Integer currentStep;

    private Integer totalStep;

    private Long currentApproverId;

    private String currentApproverName;

    private LocalDateTime submittedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    // ==================================================================
    //  当前状态下允许的动作
    // ==================================================================

    /**
     * 允许的动作标记。
     *
     * <p><b>为什么不把这些判断留给前端</b>：状态规则已经在后端
     * {@link com.enterprise.workorder.enums.TicketStatus} 里集中定义了一次。
     * 若前端再写一份 {@code status === "DRAFT" || status === "REJECTED"}，
     * 两边就有了两份会各自演进的规则 —— 后端的"已撤回也能改"上线后，
     * 前端可能仍旧把按钮藏起来，表现为"功能明明做了却点不到"，
     * 而且这类不一致只能靠人工比对代码发现。</p>
     *
     * <p>后端直接给出结论，前端只负责按布尔值渲染按钮。将来加状态、改规则，
     * 只改后端一处，前端自动跟随。</p>
     *
     * <p>注意：这些标记只表达"状态层面允不允许"。能否真正操作还要看权限 ——
     * 例如 {@code editable} 为 true 但当前用户不是创建人，改单仍会被 403 拦下。
     * 前端要用它控制按钮是否可点，而不是当作操作一定会成功的保证。</p>
     */
    @Schema(description = "当前状态是否允许修改内容")
    private Boolean editable;

    @Schema(description = "当前状态是否允许删除")
    private Boolean deletable;

    @Schema(description = "当前状态是否允许提交")
    private Boolean submittable;

    @Schema(description = "当前状态是否允许撤回")
    private Boolean withdrawable;

    @Schema(description = "当前状态是否允许作废")
    private Boolean cancelable;

    @Schema(description = "当前状态是否允许审批")
    private Boolean approvable;

    /**
     * 当前登录人是否就是本单待办人。
     *
     * <p>与 {@code approvable} 配合使用：前者是"该不该显示审批按钮"，
     * 这个是"该不该给我显示"。只看 approvable 会让所有审批人都在待办里
     * 看到一张不属于自己的单的审批按钮。</p>
     */
    @Schema(description = "当前登录人是否为本单待办人")
    private Boolean currentApprover;
}
