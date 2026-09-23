package com.enterprise.workorder.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 催办工单的请求体。
 *
 * <p><b>为什么不复用 {@link TicketCancelRequest}</b>：两个类字段一模一样（都是一个可选说明），
 * 合并成"通用说明请求体"看起来能省一个文件。但它们是两个不同业务动作的入参，
 * 现在的相同纯属巧合：作废说明写的是"为什么不办了"（对未来的读者解释），
 * 催办说明写的是"请你快点"（对某个人的喊话）。将来催办可能要加"期望完成时间"，
 * 作废要加"是否需要归档"，那时再拆开就得改所有调用方的方法签名 ——
 * 而按业务动作各留一个 DTO，改动范围永远只限一个动作。</p>
 *
 * <p>这也是本项目 DTO 的一贯做法：{@code TicketCreateRequest} 与
 * {@code TicketUpdateRequest} 字段高度重合却仍是两个类，理由相同。</p>
 */
@Data
public class TicketUrgeRequest {

    /** 催办说明，可选。留空时通知里只说"申请人希望尽快处理" */
    @Size(max = 500, message = "催办说明长度不能超过500")
    private String reason;
}