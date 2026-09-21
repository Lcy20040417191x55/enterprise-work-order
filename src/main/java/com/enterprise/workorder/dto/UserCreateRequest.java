package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 新建用户的请求体。
 *
 * <p>继承 UserSaveRequest 并追加 password 字段 —— 新建时管理员要给一个初始密码，
 * 后端会做 BCrypt 加密后落库。修改时走 UserSaveRequest（不含密码），
 * 两者的字段集不同，分开定义比"同一个类里 password 可空"更安全：
 * 后者在修改接口上意味着"不传就不改"，而 BCrypt 加密空串会产生一个
 * 永远匹配不上任何明文的密文，一旦误传等于把人锁死。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserCreateRequest extends UserSaveRequest {

    /**
     * 初始密码。这里只限长度下限（与前端一致），复杂度规则放在
     * 修改密码接口上统一做 —— 新建时的初始密码通常由管理员随手设，
     * 要求过严反而会逼着管理员把密码写在便签上发给员工。
     */
    @NotBlank(message = "初始密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度必须在6到64之间")
    private String password;
}
