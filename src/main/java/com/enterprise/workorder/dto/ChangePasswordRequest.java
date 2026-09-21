package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求体（本人操作）。
 *
 * <p>新密码的复杂度规则放这里而不是 Service 里：这是纯粹的输入形状约束，
 * 跟业务流程无关。放在 DTO 上，@Valid 在 Controller 入口就拦住了，
 * Service 里不需要再多一个 if。</p>
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度必须在6到64之间")
    private String newPassword;
}
