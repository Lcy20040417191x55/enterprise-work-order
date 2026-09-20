package com.enterprise.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("department")
public class Department extends BaseEntity {

    private String name;

    /** 上级部门ID，0 表示顶级 */
    private Long parentId;

    /** 部门主管用户ID，审批链中的 DEPT_LEADER 指向此人 */
    private Long leaderId;

    private Integer sort;
}