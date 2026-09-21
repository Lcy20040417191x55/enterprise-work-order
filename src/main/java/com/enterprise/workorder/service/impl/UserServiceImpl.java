package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.ChangePasswordRequest;
import com.enterprise.workorder.dto.ResetPasswordRequest;
import com.enterprise.workorder.dto.UserCreateRequest;
import com.enterprise.workorder.dto.UserQuery;
import com.enterprise.workorder.dto.UserSaveRequest;
import com.enterprise.workorder.dto.UserVO;
import com.enterprise.workorder.entity.Department;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.mapper.DepartmentMapper;
import com.enterprise.workorder.mapper.SysUserMapper;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final DepartmentMapper departmentMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public SysUser getByUsername(String username) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
    }

    @Override
    public SysUser getById(Long id) {
        return sysUserMapper.selectById(id);
    }

    @Override
    public List<SysUser> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectBatchIds(ids);
    }

    // ==================================================================
    //  管理员操作
    // ==================================================================

    @Override
    public IPage<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(query.getKeyword()), SysUser::getUsername, query.getKeyword())
                .or()
                .like(StringUtils.hasText(query.getKeyword()), SysUser::getRealName, query.getKeyword())
                .eq(query.getDepartmentId() != null, SysUser::getDepartmentId, query.getDepartmentId())
                .eq(StringUtils.hasText(query.getRoleCode()), SysUser::getRoleCode, query.getRoleCode())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .orderByAsc(SysUser::getId);

        IPage<SysUser> page = sysUserMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        List<Long> departmentIds = page.getRecords().stream()
                .map(SysUser::getDepartmentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Department> departmentMap = departmentMapper.selectBatchIds(departmentIds).stream()
                .collect(Collectors.toMap(Department::getId, Function.identity()));

        return page.convert(this::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO create(UserCreateRequest request) {
        requireUsernameNotUsed(request.getUsername(), null);
        requireRoleValid(request.getRoleCode());

        SysUser user = new SysUser();
        applyTo(user, request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        sysUserMapper.insert(user);

        log.info("用户已创建: {} ({}) 角色={}", user.getUsername(), user.getRealName(), user.getRoleCode());
        return toVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserSaveRequest request) {
        SysUser user = id == null ? null : sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        requireRoleValid(request.getRoleCode());

        // 登录名不可修改：它是 JWT 的 identity claim，改了等于让所有已发 token 失效
        user.setRealName(request.getRealName().trim());
        user.setEmail(StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null);
        user.setPhone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null);
        user.setDepartmentId(request.getDepartmentId());
        user.setRoleCode(request.getRoleCode());
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        sysUserMapper.updateById(user);

        log.info("用户已修改: {} ({})", user.getUsername(), user.getRealName());
        return toVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id, Integer status) {
        SysUser user = id == null ? null : sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException("status 只能为 0（禁用）或 1（启用）");
        }
        // 不允许管理员把自己禁用，否则整个系统会没有管理员可用
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId.equals(id) && status == 0) {
            throw new BusinessException("不能禁用自己");
        }
        user.setStatus(status);
        sysUserMapper.updateById(user);

        log.info("用户 {} 已{}", user.getUsername(), status == 1 ? "启用" : "禁用");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, ResetPasswordRequest request) {
        SysUser user = id == null ? null : sysUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        sysUserMapper.updateById(user);
        log.info("管理员已重置用户 {} 的密码", user.getUsername());
    }

    // ==================================================================
    //  本人操作
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordRequest request) {
        Long userId = SecurityUtils.getUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        sysUserMapper.updateById(user);
        log.info("用户 {} 修改了密码", user.getUsername());
    }

    // ==================================================================
    //  内部方法
    // ==================================================================

    private void applyTo(SysUser user, UserSaveRequest request) {
        user.setUsername(request.getUsername().trim().toLowerCase());
        user.setRealName(request.getRealName().trim());
        user.setEmail(StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null);
        user.setPhone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null);
        user.setDepartmentId(request.getDepartmentId());
        user.setRoleCode(request.getRoleCode());
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
    }

    private void requireUsernameNotUsed(String username, Long excludeId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username.trim().toLowerCase());
        if (excludeId != null) {
            wrapper.ne(SysUser::getId, excludeId);
        }
        if (sysUserMapper.exists(wrapper)) {
            throw new BusinessException("登录名「" + username + "」已被占用");
        }
    }

    private void requireRoleValid(String roleCode) {
        try {
            RoleCode.valueOf(roleCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("角色「" + roleCode + "」不合法，"
                    + "可选值: EMPLOYEE / APPROVER / ADMIN");
        }
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setDepartmentId(user.getDepartmentId());
        vo.setRoleCode(user.getRoleCode());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        if (user.getDepartmentId() != null) {
            Department dept = departmentMapper.selectById(user.getDepartmentId());
            if (dept != null) {
                vo.setDepartmentName(dept.getName());
            }
        }
        return vo;
    }
}
