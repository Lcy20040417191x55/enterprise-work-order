package com.enterprise.workorder.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 工单列表查询条件。
 *
 * <p>分页参数用注解约束而不是在 Service 里手工判断：注解的报错信息由
 * GlobalExceptionHandler 统一转成 400，且约束范围与分页插件的 maxLimit 对齐，
 * 避免"插件截断但 total 仍按原值算"导致的分页元数据错乱。</p>
 */
@Data
public class TicketQuery {

    /** 工单状态，取值见 TicketStatus；非法值由 Service 校验 */
    private String status;

    private Long typeId;

    /** 模糊匹配标题与单号 */
    private String keyword;

    /**
     * 页码。
     *
     * <p><b>为什么必须加 @NotNull</b>：{@code ?pageNum=}（值为空串）会被 Spring 的
     * 类型转换器转成 null，而 @Min 对 null 是放行的（它只管有值时的范围）。
     * 于是 null 一路传到 Page 构造函数，自动拆箱时抛 NPE，最终表现为 500 ——
     * 明明是调用方参数写错，却报成服务端故障。加上 @NotNull 后变成 400，
     * 与其它参数校验的错误码保持一致。</p>
     */
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码最小为1")
    private Integer pageNum = 1;

    /**
     * 每页条数。上限与 MybatisPlusConfig 中分页插件的 maxLimit 保持一致：
     * 若不在这里拦住，插件会把超限的 size 悄悄截断到 200，
     * 但 Count 查询仍按原始语义执行，前端拿到的 total 与实际页数对不上。
     */
    /** 同 pageNum，空串会被转成 null，必须显式拒绝 */
    @NotNull(message = "每页条数不能为空")
    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 200, message = "每页条数最大为200")
    private Integer pageSize = 10;

    /** mine=我发起的 / todo=我的待办 / done=我已审批的 / all=全部（管理员） */
    private String scope;
}