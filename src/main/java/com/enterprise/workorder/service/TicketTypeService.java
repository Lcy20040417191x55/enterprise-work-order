package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.TicketTypeSaveRequest;
import com.enterprise.workorder.entity.TicketType;

import java.util.List;

/**
 * 工单类型服务。
 */
public interface TicketTypeService {

    /** 查询所有启用的工单类型（给创建工单的下拉框用） */
    List<TicketType> listEnabled();

    /** 查询全部类型，含已停用（管理页用） */
    List<TicketType> listAll();

    /** 新建类型。code 必须唯一 */
    TicketType create(TicketTypeSaveRequest request);

    /** 修改类型。修改审批链会影响"尚未提交"与"审批中"后续级次的派发，见实现类说明 */
    TicketType update(Long id, TicketTypeSaveRequest request);

    /** 删除类型。已被工单引用的类型不允许删除 */
    void delete(Long id);
}
