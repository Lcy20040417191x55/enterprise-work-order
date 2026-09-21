package com.enterprise.workorder.service.impl;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.LoginRequest;
import com.enterprise.workorder.dto.LoginResponse;
import com.enterprise.workorder.dto.UserVO;
import com.enterprise.workorder.entity.Department;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.mapper.DepartmentMapper;
import com.enterprise.workorder.security.JwtUtil;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.AuthService;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final DepartmentMapper departmentMapper;

    @Override
    public LoginResponse login(LoginRequest request) {
        SysUser user = userService.getByUsername(request.getUsername());

        // 用户不存在与密码错误返回同一提示，避免被枚举出有效用户名
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败，用户名: {}", request.getUsername());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRoleCode());
        log.info("用户登录成功: {} ({})", user.getUsername(), user.getRealName());

        return new LoginResponse(token, user.getId(), user.getUsername(),
                user.getRealName(), user.getRoleCode());
    }

    @Override
    public LoginResponse currentUser() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser user = userService.getById(loginUser.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        return new LoginResponse(null, user.getId(), user.getUsername(),
                user.getRealName(), user.getRoleCode());
    }

    @Override
    public UserVO currentUserDetail() {
        Long userId = SecurityUtils.getUserId();
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setRoleCode(user.getRoleCode());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        if (user.getDepartmentId() != null) {
            Department dept = departmentMapper.selectById(user.getDepartmentId());
            if (dept != null) {
                vo.setDepartmentName(dept.getName());
            }
        }
        return vo;
    }
}
