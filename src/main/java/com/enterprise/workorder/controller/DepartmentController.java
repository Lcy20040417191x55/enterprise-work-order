package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.DepartmentSaveRequest;
import com.enterprise.workorder.dto.DepartmentVO;
import com.enterprise.workorder.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理接口（仅管理员）。
 */
@Tag(name = "部门管理", description = "管理员对部门的增删改查")
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @Operation(summary = "查询部门树")
    @GetMapping("/tree")
    public Result<List<DepartmentVO>> tree() {
        return Result.success(departmentService.tree());
    }

    @Operation(summary = "查询扁平部门列表")
    @GetMapping
    public Result<List<DepartmentVO>> list() {
        return Result.success(departmentService.listFlat());
    }

    @Operation(summary = "新建部门")
    @PostMapping
    public Result<DepartmentVO> create(@Valid @RequestBody DepartmentSaveRequest request) {
        return Result.success("部门已创建", departmentService.create(request));
    }

    @Operation(summary = "修改部门")
    @PutMapping("/{id}")
    public Result<DepartmentVO> update(@PathVariable Long id,
                                       @Valid @RequestBody DepartmentSaveRequest request) {
        return Result.success("部门已修改", departmentService.update(id, request));
    }

    @Operation(summary = "删除部门")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return Result.success("部门已删除", null);
    }
}
