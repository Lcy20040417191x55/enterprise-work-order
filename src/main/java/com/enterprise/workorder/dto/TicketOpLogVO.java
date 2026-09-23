package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单操作日志展示对象。
 *
 * <p><b>注意这里刻意没有 ticketId 字段</b>：日志永远是在"某张工单的详情页"里展示的，
 * 调用方已经知道自己请求的是哪张单，把它塞进每一行的 JSON 只是重复。
 * VO 的职责是给前端"渲染这一行所需要的全部信息"，多给的是噪音。</p>
 */
@Data
public class TicketOpLogVO {

    private Long id;

    @Schema(description = "操作人ID，null 表示系统动作")
    private Long operatorId;

    @Schema(description = "操作人姓名快照，系统动作时为「系统」")
    private String operatorName;

    /** 取值见 TicketOpAction */
    private String action;

    @Schema(description = "操作类型的中文名，由后端给出，前端不必自己维护映射")
    private String actionLabel;

    private String detail;

    private LocalDateTime createdAt;
}