package com.enterprise.workorder.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门树节点。
 *
 * <p>children 由 Service 层按 parentId 组装 —— 数据库里只存扁平的 parent_id，
 * 树形结构是展示层的概念，不应该让前端自己拿着 id/parentId 去拼。
 * 拼树是 O(n) 的一遍扫描，放后端做一次比前端每个页面各拼一次便宜。</p>
 */
@Data
public class DepartmentVO {

    private Long id;

    private String name;

    private Long parentId;

    private Long leaderId;

    private String leaderName;

    private Integer sort;

    /** 该部门下的在职用户数（不含禁用），用于管理页展示规模 */
    private Long userCount;

    private LocalDateTime createdAt;

    private List<DepartmentVO> children;
}
