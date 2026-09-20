package com.enterprise.workorder.service;

import com.enterprise.workorder.entity.SysUser;

import java.util.Collection;
import java.util.List;

public interface UserService {

    SysUser getByUsername(String username);

    SysUser getById(Long id);

    /**
     * 按主键批量查询。
     *
     * <p>存在的意义只有一个：让列表接口一次查完，而不是每行查一次。
     * 见 TicketServiceImpl#toVOList 的说明 —— 列表页 N 行各查 4 次，
     * 10 行的页面就要发 40 条 SQL，这是最容易被忽视、又最容易在数据量上来后爆炸的性能问题。</p>
     *
     * @param ids 主键集合；为空时直接返回空列表，不产生 SQL
     */
    List<SysUser> listByIds(Collection<Long> ids);
}
