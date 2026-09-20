package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审批请求。
 *
 * <p><b>comment 为什么不标 @NotBlank</b>：通过工单时留意见是可选的，
 * 只有驳回才必须说明理由。这种"同一个字段、按另一个字段的取值决定是否必填"的规则，
 * Bean Validation 的标准注解表达不了（需要写自定义类级约束）。
 * 因此这里用 @Size 管长度上限，把"驳回必填"的语义放在
 * TicketServiceImpl#approve 里 —— 紧挨着它要保护的那段业务逻辑，读代码时不会错过。</p>
 */
@Data
public class ApprovalRequest {

    /** APPROVE 通过 / REJECT 驳回 */
    @NotBlank(message = "审批动作不能为空")
    private String action;

    /**
     * 审批意见。通过时可空；驳回时必填（由 Service 校验），
     * 否则申请人只知道被拒、不知道改哪里。
     */
    @Size(max = 500, message = "审批意见长度不能超过500")
    private String comment;
}
