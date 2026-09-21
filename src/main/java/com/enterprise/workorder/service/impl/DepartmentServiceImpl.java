package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.DepartmentSaveRequest;
import com.enterprise.workorder.dto.DepartmentVO;
import com.enterprise.workorder.entity.Department;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.mapper.DepartmentMapper;
import com.enterprise.workorder.mapper.SysUserMapper;
import com.enterprise.workorder.service.DepartmentService;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final SysUserMapper sysUserMapper;
    private final UserService userService;

    @Override
    public List<DepartmentVO> tree() {
        List<Department> all = departmentMapper.selectList(new LambdaQueryWrapper<Department>()
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId));

        List<Long> leaderIds = all.stream()
                .map(Department::getLeaderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SysUser> leaderMap = userService.listByIds(leaderIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u));

        Map<Long, Long> userCountMap = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .isNotNull(SysUser::getDepartmentId)).stream()
                .collect(Collectors.groupingBy(SysUser::getDepartmentId, Collectors.counting()));

        Map<Long, List<DepartmentVO>> byParent = all.stream()
                .map(d -> toVO(d, leaderMap, userCountMap))
                .collect(Collectors.groupingBy(DepartmentVO::getParentId));

        // 组装树：顶级（parentId=0）作为根，其余按 parentId 挂到父节点下
        List<DepartmentVO> roots = byParent.getOrDefault(0L, List.of()).stream()
                .sorted(Comparator.comparing(DepartmentVO::getSort).thenComparing(DepartmentVO::getId))
                .toList();
        for (DepartmentVO root : roots) {
            root.setChildren(buildChildren(root.getId(), byParent));
        }
        return roots;
    }

    @Override
    public List<DepartmentVO> listFlat() {
        List<Department> all = departmentMapper.selectList(new LambdaQueryWrapper<Department>()
                .orderByAsc(Department::getSort)
                .orderByAsc(Department::getId));
        List<Long> leaderIds = all.stream()
                .map(Department::getLeaderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SysUser> leaderMap = userService.listByIds(leaderIds).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u));
        Map<Long, Long> userCountMap = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .isNotNull(SysUser::getDepartmentId)).stream()
                .collect(Collectors.groupingBy(SysUser::getDepartmentId, Collectors.counting()));

        return all.stream().map(d -> toVO(d, leaderMap, userCountMap)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DepartmentVO create(DepartmentSaveRequest request) {
        requireNameNotUsed(request.getName().trim(), null);
        if (request.getParentId() != null && request.getParentId() != 0) {
            requireDepartmentExists(request.getParentId());
        }

        Department dept = new Department();
        applyTo(dept, request);
        departmentMapper.insert(dept);
        log.info("部门已创建: {} (id={})", dept.getName(), dept.getId());
        return toVO(dept, Map.of(), Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DepartmentVO update(Long id, DepartmentSaveRequest request) {
        Department dept = id == null ? null : departmentMapper.selectById(id);
        if (dept == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }
        requireNameNotUsed(request.getName().trim(), id);

        // 不能把自己设为自己的子部门（或更深的后代），否则审批链解析会死循环
        if (request.getParentId() != null && request.getParentId() != 0) {
            if (request.getParentId().equals(id)) {
                throw new BusinessException("不能将部门的上级设为自身");
            }
            requireNotDescendant(id, request.getParentId());
            requireDepartmentExists(request.getParentId());
        }

        applyTo(dept, request);
        departmentMapper.updateById(dept);
        log.info("部门已修改: {} (id={})", dept.getName(), dept.getId());
        return toVO(dept, Map.of(), Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Department dept = id == null ? null : departmentMapper.selectById(id);
        if (dept == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "部门不存在");
        }

        Long childCount = departmentMapper.selectCount(new LambdaQueryWrapper<Department>()
                .eq(Department::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BusinessException("该部门下有 " + childCount + " 个子部门，不能删除。请先删除或移动子部门");
        }

        Long userCount = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDepartmentId, id));
        if (userCount != null && userCount > 0) {
            throw new BusinessException("该部门下有 " + userCount + " 名员工，不能删除。请先转移员工到其他部门");
        }

        departmentMapper.deleteById(id);
        log.info("部门已删除: {} (id={})", dept.getName(), dept.getId());
    }

    // ==================================================================
    //  内部方法
    // ==================================================================

    private void applyTo(Department dept, DepartmentSaveRequest request) {
        dept.setName(request.getName().trim());
        dept.setParentId(request.getParentId() == null ? 0L : request.getParentId());
        dept.setLeaderId(request.getLeaderId());
        dept.setSort(request.getSort() == null ? 0 : request.getSort());
    }

    private void requireNameNotUsed(String name, Long excludeId) {
        LambdaQueryWrapper<Department> wrapper = new LambdaQueryWrapper<Department>()
                .eq(Department::getName, name);
        if (excludeId != null) {
            wrapper.ne(Department::getId, excludeId);
        }
        if (departmentMapper.exists(wrapper)) {
            throw new BusinessException("部门名称「" + name + "」已存在");
        }
    }

    private void requireDepartmentExists(Long parentId) {
        if (departmentMapper.selectById(parentId) == null) {
            throw new BusinessException("上级部门(id=" + parentId + ")不存在");
        }
    }

    /**
     * 校验 targetId 不是 startId 的后代。
     *
     * <p>如果不拦住，A 改上级为 B、B 改上级为 A 就成环了。审批链的 DEPT_LEADER
     * 沿部门树向上找主管的逻辑（ApprovalFlowResolver#resolveDepartmentLeader）
     * 会因为环而永远走不到顶级，最终靠 MAX_DEPARTMENT_DEPTH 兜底返回 null，
     * 表现为"审批人解析不到"，排查起来非常绕。在这里就掐死这个可能。</p>
     */
    private void requireNotDescendant(Long startId, Long targetId) {
        Long currentId = targetId;
        int depth = 0;
        while (currentId != null && currentId != 0 && depth < 20) {
            if (currentId.equals(startId)) {
                throw new BusinessException("不能将部门的上上级设为自身的子部门（会造成部门环）");
            }
            Department parent = departmentMapper.selectById(currentId);
            currentId = parent == null ? null : parent.getParentId();
            depth++;
        }
    }

    private List<DepartmentVO> buildChildren(Long parentId, Map<Long, List<DepartmentVO>> byParent) {
        List<DepartmentVO> children = byParent.getOrDefault(parentId, List.of()).stream()
                .sorted(Comparator.comparing(DepartmentVO::getSort).thenComparing(DepartmentVO::getId))
                .toList();
        for (DepartmentVO child : children) {
            child.setChildren(buildChildren(child.getId(), byParent));
        }
        return children;
    }

    private DepartmentVO toVO(Department dept,
                              Map<Long, SysUser> leaderMap,
                              Map<Long, Long> userCountMap) {
        DepartmentVO vo = new DepartmentVO();
        vo.setId(dept.getId());
        vo.setName(dept.getName());
        vo.setParentId(dept.getParentId());
        vo.setLeaderId(dept.getLeaderId());
        vo.setSort(dept.getSort());
        vo.setCreatedAt(dept.getCreatedAt());
        vo.setUserCount(userCountMap.getOrDefault(dept.getId(), 0L));
        if (dept.getLeaderId() != null) {
            SysUser leader = leaderMap.get(dept.getLeaderId());
            if (leader != null) {
                vo.setLeaderName(leader.getRealName());
            }
        }
        return vo;
    }
}
