package com.enterprise.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建/修改部门的请求体。
 */
@Data
public class DepartmentSaveRequest {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 64, message = "部门名称长度不能超过64")
    private String name;

    /** 上级部门ID，0 表示顶级；null 视为顶级 */
    @NotNull(message = "上级部门不能为空")
    private Long parentId;

    /** 部门主管用户ID；null 表示暂不设置 */
    private Long leaderId;

    private Integer sort = 0;
}
