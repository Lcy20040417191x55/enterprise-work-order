package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.LoginRequest;
import com.enterprise.workorder.dto.LoginResponse;
import com.enterprise.workorder.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "登录与当前用户")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("登录成功", authService.login(request));
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public Result<LoginResponse> me() {
        return Result.success(authService.currentUser());
    }
}