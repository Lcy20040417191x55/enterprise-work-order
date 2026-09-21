package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员重置密码请求体。
 *
 * <p>与 ChangePasswordRequest 分开定义而不是共用：前者要求 oldPassword
 * （本人验证身份），后者没有（管理员替别人重置，不存在"原密码"一说）。
 * 塞进同一个类会让 oldPassword 变成"有时必填有时不填"，校验语义混乱。</p>
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度必须在6到64之间")
    private String newPassword;
}
