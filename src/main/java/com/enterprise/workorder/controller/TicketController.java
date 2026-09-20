package com.enterprise.workorder.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.ApprovalRequest;
import com.enterprise.workorder.dto.TicketCancelRequest;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketUpdateRequest;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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
 * 工单接口。
 *
 * <p><b>接口划分遵循"业务动作即路径"</b>：提交、审批、撤回、作废这些是状态机上的动作，
 * 各自独立成 POST 子路径；只有"改内容"这种纯数据修改才用 PUT 语义。
 * 好处是权限规则能直接照抄路径写（如 /approve 限 ADMIN/APPROVER），
 * 不必在控制器里写 if 判断。</p>
 */
@Tag(name = "工单", description = "工单创建、修改、提交、审批、撤回、作废与查询")
@Validated
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @Operation(summary = "创建工单（草稿）")
    @PostMapping
    public Result<Ticket> create(@Valid @RequestBody TicketCreateRequest request) {
        return Result.success("工单已创建", ticketService.create(request));
    }

    @Operation(summary = "修改工单内容（仅草稿与已驳回，仅创建人）")
    @PutMapping("/{id}")
    public Result<TicketVO> update(@PathVariable Long id,
                                   @Valid @RequestBody TicketUpdateRequest request) {
        return Result.success("工单已更新", ticketService.update(id, request));
    }

    @Operation(summary = "删除草稿（仅创建人，且从未提交过的草稿）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return Result.success("草稿已删除", null);
    }

    @Operation(summary = "提交工单，进入审批流")
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        ticketService.submit(id);
        return Result.success("已提交，等待审批", null);
    }

    @Operation(summary = "审批：APPROVE 通过 / REJECT 驳回（驳回必须填意见）")
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @Valid @RequestBody ApprovalRequest request) {
        ticketService.approve(id, request.getAction(), request.getComment());
        return Result.success("审批完成", null);
    }

    @Operation(summary = "撤回工单（仅审批中的单，仅创建人）")
    @PostMapping("/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        ticketService.withdraw(id);
        return Result.success("已撤回", null);
    }

    @Operation(summary = "作废工单（仅草稿与已驳回，仅创建人）")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id,
                               @Valid @RequestBody(required = false) TicketCancelRequest request) {
        ticketService.cancel(id, request == null ? null : request.getComment());
        return Result.success("工单已作废", null);
    }

    @Operation(summary = "分页查询工单",
            description = "scope: mine 我发起的 / todo 我的待办 / done 我已审批的 / all 全部（管理员）")
    @GetMapping
    public Result<IPage<TicketVO>> page(@Valid TicketQuery query) {
        return Result.success(ticketService.page(query));
    }

    @Operation(summary = "工单详情")
    @GetMapping("/{id}")
    public Result<TicketVO> detail(@PathVariable Long id) {
        return Result.success(ticketService.detail(id));
    }

    @Operation(summary = "审批轨迹")
    @GetMapping("/{id}/history")
    public Result<List<ApprovalRecord>> history(@PathVariable Long id) {
        return Result.success(ticketService.history(id));
    }
}
