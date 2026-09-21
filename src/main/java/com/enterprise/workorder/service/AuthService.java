package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.LoginRequest;
import com.enterprise.workorder.dto.LoginResponse;
import com.enterprise.workorder.dto.UserVO;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    /** 获取当前登录用户信息 */
    LoginResponse currentUser();

    /** 获取当前登录用户完整信息（含部门名等展示字段） */
    UserVO currentUserDetail();
}
