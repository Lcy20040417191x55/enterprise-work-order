package com.enterprise.workorder.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.ResetPasswordRequest;
import com.enterprise.workorder.dto.UserCreateRequest;
import com.enterprise.workorder.dto.UserQuery;
import com.enterprise.workorder.dto.UserSaveRequest;
import com.enterprise.workorder.dto.UserVO;
import com.enterprise.workorder.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（仅管理员）。
 */
@Tag(name = "用户管理", description = "管理员对用户的增删改查与密码重置")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "分页查询用户列表")
    @GetMapping
    public Result<IPage<UserVO>> page(@Valid UserQuery query) {
        return Result.success(userService.page(query));
    }

    @Operation(summary = "新建用户")
    @PostMapping
    public Result<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.success("用户已创建", userService.create(request));
    }

    @Operation(summary = "修改用户信息")
    @PutMapping("/{id}")
    public Result<UserVO> update(@PathVariable Long id,
                                 @Valid @RequestBody UserSaveRequest request) {
        return Result.success("用户已修改", userService.update(id, request));
    }

    @Operation(summary = "启用或禁用用户")
    @PutMapping("/{id}/status")
    public Result<Void> toggleStatus(@PathVariable Long id,
                                     @RequestParam Integer status) {
        userService.toggleStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "重置用户密码")
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return Result.success("密码已重置", null);
    }
}
