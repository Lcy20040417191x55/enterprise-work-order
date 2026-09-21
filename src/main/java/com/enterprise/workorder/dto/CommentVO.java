package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单评论展示对象 */
@Data
public class CommentVO {

    private Long id;

    private Long ticketId;

    private Long userId;

    private String userName;

    private String content;

    private LocalDateTime createdAt;

    @Schema(description = "当前登录人是否可以撤回这条评论（仅本人可撤）")
    private Boolean deletable;
}
