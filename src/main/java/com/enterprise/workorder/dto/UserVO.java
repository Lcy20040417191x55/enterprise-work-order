package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户列表/详情展示对象。
 *
 * <p>不直接返回 SysUser 实体，最核心的原因是 password 字段 ——
 * 虽然 jackson 配置了 non_null，但只要实体被序列化，password 字段就
 * 有被序列化出去的可能（哪怕值是 BCrypt 密文也不该让前端看到）。
 * 用一个没有 password 字段的 VO，从类型层面杜绝这个风险。</p>
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String realName;

    private String email;

    private String phone;

    private Long departmentId;

    private String departmentName;

    private String roleCode;

    @Schema(description = "1 启用 / 0 禁用")
    private Integer status;

    private LocalDateTime createdAt;
}
