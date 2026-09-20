package com.enterprise.workorder.support;

import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.security.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 测试用登录辅助。
 *
 * <p>直接操作 SecurityContextHolder，模拟 JwtAuthenticationFilter 完成认证后的状态 ——
 * 而不是 Mock {@code SecurityUtils}。这样被测代码走的是与运行时完全相同的取值路径，
 * "Service 读不到用户"这类问题才会在测试里暴露出来。</p>
 */
public final class AuthTestSupport {

    private AuthTestSupport() {
    }

    /**
     * 以指定账号登录。
     *
     * <p>账号数据取自 sql/schema.sql 的初始化数据，与生产结构一致，不额外造数。</p>
     */
    public static void loginAs(long userId) {
        String username = usernameOf(userId);
        String role = roleOf(userId);
        LoginUser user = new LoginUser(userId, username, username, role, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** 清空登录态，避免用例之间互相污染 */
    public static void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 与 sql/schema.sql 的 sys_user 初始化数据一一对应 */
    public static String usernameOf(long userId) {
        return switch ((int) userId) {
            case 1 -> "admin";
            case 2 -> "zhangsan";
            case 3 -> "lisi";
            case 4 -> "wangwu";
            default -> "user" + userId;
        };
    }

    public static String roleOf(long userId) {
        return switch ((int) userId) {
            case 1 -> RoleCode.ADMIN.name();
            case 2 -> RoleCode.EMPLOYEE.name();
            default -> RoleCode.APPROVER.name();
        };
    }
}
