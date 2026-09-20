package com.enterprise.workorder.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录后在 SecurityContext 中保存的当前用户信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;

    private String username;

    private String realName;

    private String roleCode;

    private Long departmentId;
}