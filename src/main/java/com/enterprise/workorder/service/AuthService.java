package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.LoginRequest;
import com.enterprise.workorder.dto.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    /** 获取当前登录用户信息 */
    LoginResponse currentUser();
}