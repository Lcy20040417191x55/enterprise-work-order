package com.enterprise.workorder.security;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户的取用入口。
 * Service 层通过这里获取操作人，不要从 Controller 逐层传 userId。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 可能为 null，用于允许匿名的场景 */
    public static LoginUser getLoginUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }

    /** 必须已登录，否则抛 401 */
    public static LoginUser getLoginUser() {
        LoginUser user = getLoginUserOrNull();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return user;
    }

    public static Long getUserId() {
        return getLoginUser().getUserId();
    }

    public static String getRoleCode() {
        return getLoginUser().getRoleCode();
    }
}