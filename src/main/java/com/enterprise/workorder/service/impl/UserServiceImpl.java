package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.mapper.SysUserMapper;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;

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
        // 空集合必须提前返回。selectBatchIds 收到空集合时会生成
        // "WHERE id IN ()" 这种非法 SQL，表现为莫名其妙的语法错误。
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectBatchIds(ids);
    }
}
