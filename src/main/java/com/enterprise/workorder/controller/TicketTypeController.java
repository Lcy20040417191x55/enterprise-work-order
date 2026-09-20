package com.enterprise.workorder.controller;

import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.TicketTypeSaveRequest;
import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.service.TicketTypeService;
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
 * 工单类型接口。
 *
 * <p><b>读写权限分开</b>：查询对所有登录用户开放（创建工单的下拉框要用），
 * 增删改限 ADMIN —— 类型与审批链是流程配置，改坏了影响所有人。</p>
 */
@Tag(name = "工单类型", description = "工单类型的查询与配置")
@RestController
@RequestMapping("/api/ticket-types")
@RequiredArgsConstructor
public class TicketTypeController {

    private final TicketTypeService ticketTypeService;

    @Operation(summary = "查询所有启用的工单类型（创建工单时用）")
    @GetMapping
    public Result<List<TicketType>> list() {
        return Result.success(ticketTypeService.listEnabled());
    }

    @Operation(summary = "查询全部工单类型，含已停用（管理页用，仅管理员）")
    @GetMapping("/all")
    public Result<List<TicketType>> listAll() {
        return Result.success(ticketTypeService.listAll());
    }

    @Operation(summary = "新建工单类型（仅管理员）")
    @PostMapping
    public Result<TicketType> create(@Valid @RequestBody TicketTypeSaveRequest request) {
        return Result.success("工单类型已创建", ticketTypeService.create(request));
    }

    @Operation(summary = "修改工单类型（仅管理员）")
    @PutMapping("/{id}")
    public Result<TicketType> update(@PathVariable Long id,
                                     @Valid @RequestBody TicketTypeSaveRequest request) {
        return Result.success("工单类型已修改", ticketTypeService.update(id, request));
    }

    @Operation(summary = "删除工单类型（仅管理员，且未被任何工单使用）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ticketTypeService.delete(id);
        return Result.success("工单类型已删除", null);
    }
}
