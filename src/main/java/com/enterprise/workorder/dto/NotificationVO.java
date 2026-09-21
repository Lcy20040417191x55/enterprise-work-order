package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 通知列表/详情展示对象 */
@Data
public class NotificationVO {

    private Long id;

    private String type;

    @Schema(description = "通知类型的中文名，未知类型原样返回")
    private String typeLabel;

    private String title;

    private String content;

    /** 关联业务ID；目前是工单ID，为空表示不可点击 */
    private Long bizId;

    private Boolean read;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
