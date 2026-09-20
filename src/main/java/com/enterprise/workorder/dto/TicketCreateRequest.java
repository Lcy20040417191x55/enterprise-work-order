package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TicketCreateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题长度不能超过128")
    private String title;

    @Size(max = 5000, message = "内容长度不能超过5000")
    private String content;

    @NotNull(message = "请选择工单类型")
    private Long typeId;

    private String priority = "NORMAL";
}