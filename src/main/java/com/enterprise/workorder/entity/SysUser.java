package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;

    /** BCrypt 密文，任何情况下不得返回给前端 */
    private String password;

    private String realName;

    private String email;

    private String phone;

    private Long departmentId;

    /** 角色编码，取值见 {@link com.enterprise.workorder.enums.RoleCode} */
    private String roleCode;

    /** 1 启用 / 0 禁用 */
    private Integer status;
}