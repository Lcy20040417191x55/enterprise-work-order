package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.entity.Department;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.mapper.DepartmentMapper;
import com.enterprise.workorder.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批链解析测试。
 *
 * <p><b>为什么直接测 Resolver 而不是通过提交工单间接测</b>：本类要覆盖的多数是
 * "配置有缺陷时"的分支 —— 部门缺主管、一个管理员都没有、部门树成环。
 * 这些场景若走 Service，会被状态校验、权限校验层层包住，断言只能看到最外层的一句话；
 * 直接测 Resolver 才能精确断言"解析到了哪个人"和"报错信息是否指出该改哪里"。</p>
 *
 * <p>@Transactional 保证测试造的部门/用户改动在用例结束后全部回滚，不污染开发数据。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("审批链解析")
class ApprovalFlowResolverTest {

    private static final long UID_ADMIN = 1L;
    private static final long UID_ZHANGSAN = 2L;
    private static final long UID_LISI = 3L;

    /** 技术部：leader_id = 3（李四） */
    private static final long DEPT_TECH = 2L;
    /** 财务部：leader_id = NULL，parent = 总公司 */
    private static final long DEPT_FINANCE = 4L;

    /** 用一个不存在的用户ID当"申请人"，避免撞上自审规避逻辑 */
    private static final long NOBODY = 999999L;

    @Autowired
    private ApprovalFlowResolver resolver;

    @Autowired
    private DepartmentMapper departmentMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @AfterEach
    void tearDown() {
        System.out.flush();
    }

    // ==================================================================
    //  正常路径
    // ==================================================================

    @Nested
    @DisplayName("正常解析")
    class Normal {

        @Test
        @DisplayName("两级链应解析成两个人")
        void shouldResolveTwoLevelFlow() {
            List<Long> chain = resolver.resolveChain("DEPT_LEADER,ADMIN", DEPT_TECH, NOBODY);

            assertThat(chain).containsExactly(UID_LISI, UID_ADMIN);
        }

        @Test
        @DisplayName("单级链的级数就是 1，不会凭空补级")
        void shouldResolveSingleLevelFlow() {
            List<Long> chain = resolver.resolveChain("DEPT_LEADER", DEPT_TECH, NOBODY);

            assertThat(chain).containsExactly(UID_LISI);
        }

        @Test
        @DisplayName("同一人在链上出现两次时应合并，否则两级审批实际只有一个关卡")
        void shouldMergeDuplicateApprovers() {
            // 技术部主管是李四，链却是两级 DEPT_LEADER —— 解析出同一个人。
            // 若不去重，申请人要等同一个人的两次审批，观感像"卡住了"。
            List<Long> chain = resolver.resolveChain("DEPT_LEADER,DEPT_LEADER", DEPT_TECH, NOBODY);

            assertThat(chain).containsExactly(UID_LISI);
        }

        @Test
        @DisplayName("审批链为空应直接拒绝，不能放行一张无人审批的单")
        void shouldRejectBlankFlow() {
            assertThatThrownBy(() -> resolver.resolveChain("  ", DEPT_TECH, NOBODY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未配置审批链");
        }
    }

    // ==================================================================
    //  部门树向上追溯
    // ==================================================================

    @Nested
    @DisplayName("部门树向上追溯")
    class DepartmentUpTrace {

        @Test
        @DisplayName("本部门没主管时，应由上级部门主管代管")
        void shouldTraceUpToParentLeader() {
            // 技术部(leader=李四) 下新建一个没有主管的子部门
            Department child = newDepartment("测试子部门", DEPT_TECH, null);

            List<Long> chain = resolver.resolveChain("DEPT_LEADER", child.getId(), NOBODY);

            assertThat(chain).containsExactly(UID_LISI);
        }

        @Test
        @DisplayName("缺主管时沿树逐级上溯，而不是只看直接上级")
        void shouldTraceUpThroughMultipleLevels() {
            Department middle = newDepartment("测试中间层", DEPT_TECH, null);
            Department leaf = newDepartment("测试末级", middle.getId(), null);

            List<Long> chain = resolver.resolveChain("DEPT_LEADER", leaf.getId(), NOBODY);

            assertThat(chain).containsExactly(UID_LISI);
        }

        @Test
        @DisplayName("整个部门树都没有主管时，回落管理员代审，而不是拒绝提交")
        void shouldFallBackToAdminWhenNoLeaderAnywhere() {
            // 财务部及其上级（总公司）都没有主管
            List<Long> chain = resolver.resolveChain("DEPT_LEADER", DEPT_FINANCE, NOBODY);

            assertThat(chain).containsExactly(UID_ADMIN);
        }

        @Test
        @DisplayName("沿树找到的主管若是申请人本人，应摘级后交给他人代审")
        void shouldSkipSelfApprovalAtTracedLeader() {
            // 新建部门没有主管，上溯到技术部主管李四 —— 但申请人就是李四。
            // 若直接派给他，这张单等于自己批自己。
            Department child = newDepartment("测试子部门", DEPT_TECH, null);

            List<Long> chain = resolver.resolveChain("DEPT_LEADER", child.getId(), UID_LISI);

            assertThat(chain).containsExactly(UID_ADMIN);
        }
    }

    // ==================================================================
    //  配置错误时的报错信息
    // ==================================================================

    @Nested
    @DisplayName("配置错误")
    class Misconfiguration {

        @Test
        @DisplayName("部门树成环时不能死循环，必须限深后兜底")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldNotHangOnCyclicDepartmentTree() {
            Department a = newDepartment("测试环A", 0L, null);
            Department b = newDepartment("测试环B", a.getId(), null);
            // 把 A 的上级指向 B，构成环：A -> B -> A -> ...
            a.setParentId(b.getId());
            departmentMapper.updateById(a);

            List<Long> chain = resolver.resolveChain("DEPT_LEADER", a.getId(), NOBODY);

            assertThat(chain).containsExactly(UID_ADMIN);
        }

        @Test
        @DisplayName("没有可选审批人时，报错要指出具体部门，而不是笼统的「请检查配置」")
        void shouldReportUnresolvableDepartmentId() {
            disableAllAdmins();

            assertThatThrownBy(() -> resolver.resolveChain("DEPT_LEADER", DEPT_FINANCE, NOBODY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("部门(id=" + DEPT_FINANCE + ")")
                    .hasMessageContaining("leader_id");
        }

        @Test
        @DisplayName("系统中没有启用的管理员时，报错要说清是 ADMIN 账号的问题")
        void shouldReportMissingAdmin() {
            disableAllAdmins();

            assertThatThrownBy(() -> resolver.resolveChain("ADMIN", DEPT_TECH, NOBODY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("管理员");
        }

        @Test
        @DisplayName("整条链都是申请人本人且无他人代审时，应明确拒绝")
        void shouldRejectWhenOnlyCreatorCanApprove() {
            disableAllAdmins();

            assertThatThrownBy(() -> resolver.resolveChain("DEPT_LEADER", DEPT_TECH, UID_LISI))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private Department newDepartment(String name, Long parentId, Long leaderId) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(parentId == null ? 0L : parentId);
        department.setLeaderId(leaderId);
        department.setSort(99);
        departmentMapper.insert(department);
        return department;
    }

    /** 停用全部管理员，用来验证"一个人都派不出来"时的行为。测试结束由事务回滚 */
    private void disableAllAdmins() {
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getRoleCode, RoleCode.ADMIN.name())
                .set(SysUser::getStatus, 0));
    }
}
