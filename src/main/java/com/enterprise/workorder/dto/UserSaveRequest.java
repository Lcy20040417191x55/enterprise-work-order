package com.enterprise.workorder.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建/修改用户的请求体。
 *
 * <p>新建与修改共用一个 DTO：字段完全相同，语义是"页面上看到什么、提交的就是什么"。
 * 密码不在这里 —— 新建时必须传、修改时永远不传（改密走独立接口），
 * 两种场景的字段集不同，硬塞进同一个类只会让校验规则互相打架。</p>
 */
@Data
public class UserSaveRequest {

    /**
     * 登录名。仅允许小写字母数字与下划线，避免 "Zhang San" 与 "zhangsan"
     * 在登录时被当成两个人。修改时后端会忽略此字段（登录名不可改），
     * 但新建必须传 —— 用 @NotBlank 在入口处拦住，而不是到 Service 里再判空。
     */
    @NotBlank(message = "登录名不能为空")
    @Size(max = 64, message = "登录名长度不能超过64")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "登录名只能由小写字母、数字、下划线组成，且以字母开头")
    private String username;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过64")
    private String realName;

    @Size(max = 128, message = "邮箱长度不能超过128")
    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(max = 32, message = "手机号长度不能超过32")
    private String phone;

    /** 所属部门ID；null 表示暂不分配部门 */
    private Long departmentId;

    /**
     * 角色编码，取值见 RoleCode。
     * 这里只限长度，"是不是合法角色"由 Service 对照枚举校验，
     * 避免把枚举词表复制到注解里 —— 复制的那份迟早会和代码里的不一致。
     */
    @NotBlank(message = "角色不能为空")
    @Size(max = 32, message = "角色编码长度不能超过32")
    private String roleCode;

    /** 1 启用 / 0 禁用；null 视为启用 */
    private Integer status = 1;
}
