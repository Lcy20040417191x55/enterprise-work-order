package com.enterprise.workorder.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户列表查询条件。
 *
 * <p>分页参数的校验规则与 TicketQuery 保持一致 —— pageNum/pageSize
 * 必须显式 @NotNull（空串会被 Spring 转成 null，@Min 对 null 放行，
 * 最终在 Page 构造函数里以 NPE 的形式炸出来）。</p>
 */
@Data
public class UserQuery {

    /** 模糊匹配登录名 / 姓名 */
    private String keyword;

    private Long departmentId;

    /** 角色编码，精确匹配；空则不过滤 */
    private String roleCode;

    /** 1 只看启用 / 0 只看禁用 / null 不过滤 */
    private Integer status;

    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码最小为1")
    private Integer pageNum = 1;

    @NotNull(message = "每页条数不能为空")
    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 200, message = "每页条数最大为200")
    private Integer pageSize = 10;
}
