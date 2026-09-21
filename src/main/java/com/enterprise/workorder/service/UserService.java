package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.dto.ChangePasswordRequest;
import com.enterprise.workorder.dto.ResetPasswordRequest;
import com.enterprise.workorder.dto.UserCreateRequest;
import com.enterprise.workorder.dto.UserQuery;
import com.enterprise.workorder.dto.UserSaveRequest;
import com.enterprise.workorder.dto.UserVO;
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

    /** 分页查询用户列表（管理员专用） */
    IPage<UserVO> page(UserQuery query);

    /** 新建用户（管理员）。密码做 BCrypt 加密后落库 */
    UserVO create(UserCreateRequest request);

    /** 修改用户基本信息（管理员）。登录名与密码不可通过此接口修改 */
    UserVO update(Long id, UserSaveRequest request);

    /** 启用或禁用用户（管理员） */
    void toggleStatus(Long id, Integer status);

    /** 管理员重置他人密码 */
    void resetPassword(Long id, ResetPasswordRequest request);

    /** 本人修改密码（需验证原密码） */
    void changePassword(ChangePasswordRequest request);
}
