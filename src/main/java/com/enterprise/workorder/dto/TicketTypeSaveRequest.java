package com.enterprise.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建/修改工单类型的请求体。
 *
 * <p><b>为什么新建与修改共用一个 DTO</b>：字段完全相同，且都没有"不传即保持原值"的语义 ——
 * 类型是配置数据，整体覆盖比局部更新更安全：管理员在页面上看到什么、提交的就是什么，
 * 不会出现"少传一个字段导致某项被悄悄保留成旧值"的困惑。</p>
 */
@Data
public class TicketTypeSaveRequest {

    /**
     * 类型编码，如 LEAVE。
     *
     * <p>用 @Pattern 而不是只判非空：编码会被写进审批链、报表和外部对接里，
     * 若允许"leave"、"Leave"、"leave " 并存，后续按 code 查询必然漏数据。
     * 在入口处一次性限死形状，比事后写各种兼容逻辑便宜得多。</p>
     *
     * <p><b>为什么这里允许小写、而由 Service 统一转大写</b>：两者若各管一段就会打架 ——
     * 若注解强制大写，管理员输入 "leave" 会在校验阶段就被 400 拒掉，
     * Service 里那行 {@code toUpperCase} 永远执行不到，等于白写。
     * 现在的分工是：注解只管"形状对不对"（字母开头、只含字母数字下划线），
     * Service 管"统一成一种写法"（转大写后再做唯一性校验）。
     * 于是 "leave" 与 "LEAVE" 会被识别成同一个类型，这正是想要的效果。</p>
     */
    @NotBlank(message = "类型编码不能为空")
    @Size(max = 32, message = "类型编码长度不能超过32")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
            message = "类型编码只能由字母、数字、下划线组成，且以字母开头，例如 LEAVE")
    private String code;

    @NotBlank(message = "类型名称不能为空")
    @Size(max = 64, message = "类型名称长度不能超过64")
    private String name;

    @Size(max = 255, message = "描述长度不能超过255")
    private String description;

    /**
     * 审批链，逗号分隔，取值只能是 DEPT_LEADER / ADMIN。
     *
     * <p>这里只做格式约束（非空、长度）。"每一项是不是合法角色"需要结合
     * ApprovalFlowResolver 的占位符常量来判断，放在 Service 里做，避免把业务词表
     * 复制到注解里 —— 复制出来的那份迟早会和代码里的常量不一致。</p>
     */
    @NotBlank(message = "审批链不能为空")
    @Size(max = 255, message = "审批链长度不能超过255")
    @Schema(example = "DEPT_LEADER,ADMIN", description = "逗号分隔，可选值 DEPT_LEADER / ADMIN")
    private String approvalFlow;

    private Integer sort = 0;

    /** 1 启用 / 0 停用；null 视为启用 */
    private Integer enabled = 1;
}
