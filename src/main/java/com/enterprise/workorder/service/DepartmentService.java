package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.DepartmentSaveRequest;
import com.enterprise.workorder.dto.DepartmentVO;

import java.util.List;

/**
 * 部门服务。
 */
public interface DepartmentService {

    /** 查询部门树。children 由后端组装，前端直接渲染 */
    List<DepartmentVO> tree();

    /** 查询扁平列表（创建工单时选部门用） */
    List<DepartmentVO> listFlat();

    /** 新建部门 */
    DepartmentVO create(DepartmentSaveRequest request);

    /** 修改部门 */
    DepartmentVO update(Long id, DepartmentSaveRequest request);

    /** 删除部门。有员工或有子部门时拒绝 */
    void delete(Long id);
}
