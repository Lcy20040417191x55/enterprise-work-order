package com.enterprise.workorder.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.common.Result;
import com.enterprise.workorder.dto.ApprovalRequest;
import com.enterprise.workorder.dto.TicketCancelRequest;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketOpLogVO;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketUpdateRequest;
import com.enterprise.workorder.dto.TicketUrgeRequest;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.service.TicketExportService;
import com.enterprise.workorder.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final TicketExportService ticketExportService;

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

    /**
     * 催办。
     *
     * <p><b>为什么不在路径或权限配置上限定"谁能催"</b>：审批、撤回这类动作的操作人是
     * 固定的某类角色，权限规则一眼能写出来。催办的操作人是"这张单的创建人" ——
     * 这是数据维度的规则而非角色维度：同一个 EMPLOYEE 在 A 单上能催、在 B 单上不能。
     * 硬写进权限配置只会得到一条形同虚设的规则，真正的判定只能在 Service 里做。</p>
     *
     * <p>请求体可省略：催办的核心信息是"谁在催哪张单"，说明是锦上添花。
     * 用户只点一下按钮、不填理由时前端会发一个空对象过来，这里照样接受。</p>
     */
    @Operation(summary = "催办工单（仅审批中的单，仅创建人，两次间隔至少30分钟）")
    @PostMapping("/{id}/urge")
    public Result<Void> urge(@PathVariable Long id,
                             @Valid @RequestBody(required = false) TicketUrgeRequest request) {
        ticketService.urge(id, request == null ? null : request.getReason());
        return Result.success("已催办，已通知当前审批人", null);
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

    /**
     * 导出 Excel。
     *
     * <p><b>为什么不返回 {@code Result<byte[]>}</b>：那样等于把整个文件先在内存里拼出来，
     * 再交给 Spring 序列化。10 万行 xlsx 大约 20~50MB，每个导出请求都要一份这么大的
     * 常驻内存，几个人同时点就足以把堆压满。这里直接把响应流交给导出服务，
     * POI 边写边发，服务端内存占用与文件大小无关。</p>
     *
     * <p><b>返回的是文件流，不是 JSON</b>，所以前端不能用普通的接口封装去调，
     * 必须声明 {@code responseType: 'blob'}；同时因为要带 JWT，
     * 也不能用 {@code window.open} 直接开链接（那样带不上 Authorization 头）。</p>
     */
    @Operation(summary = "导出工单 Excel",
            description = "筛选条件与列表接口完全一致（scope/status/typeId/keyword），导出的是全量而非当前页")
    @GetMapping("/export")
    public void export(@Valid TicketQuery query, HttpServletResponse response) throws IOException {
        String fileName = ticketExportService.buildFileName();

        // setContentType 而不是 setHeader，让 Spring 处理 charset 拼接
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // 文件名里有中文，必须 URL 编码。不编码时 Tomcat 会按 ISO-8859-1 写出响应头，
        // 浏览器收到的文件名是乱码（表现为下载下来叫 "工单导出_xxx" 变成 "????_xxx"）。
        // filename* 是 RFC 5987 定义的写法，现代浏览器优先读它。
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        // 让网关/代理知道这是流式响应，不要试图缓存整个文件
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        ticketExportService.export(query, response.getOutputStream());
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

    /**
     * 完整操作日志（含评论、附件增删、催办）。
     *
     * <p><b>为什么不让前端把 history、评论、附件三个接口自己拼成一条时间线</b>：
     * 那样前端要拉三次再自己排序，而三处的时间戳精度、序列化格式、排序口径
     * 只要有一处不同，拼出来的顺序就是错的 —— 例如"上传附件"排到了"审批通过"之后。
     * 顺序恰恰是时间线这类界面的全部价值所在。由后端一次查、一次排，
     * 顺序只有一份实现。</p>
     *
     * <p>可见性与详情页完全一致（内部复用同一份权限判定），
     * 因此不存在"看不到工单却能把它的操作日志拉出来"这条路径。</p>
     */
    @Operation(summary = "操作日志（含评论、附件、催办）")
    @GetMapping("/{id}/op-logs")
    public Result<List<TicketOpLogVO>> opLogs(@PathVariable Long id) {
        return Result.success(ticketService.opLogs(id));
    }
}
