package com.enterprise.workorder.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作废工单的请求体。
 *
 * <p>不复用 {@link ApprovalRequest}：那个类有个必填的 action 字段，
 * 而作废这个动作本身就是 action，让调用方再传一次 "CANCEL" 纯属冗余，
 * 且容易与审批接口的语义混淆。作废只需要一个可选的说明。</p>
 */
@Data
public class TicketCancelRequest {

    /** 作废原因，可选 */
    @Size(max = 500, message = "作废说明长度不能超过500")
    private String comment;
}
