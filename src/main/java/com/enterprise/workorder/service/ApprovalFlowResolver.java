package com.enterprise.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.entity.Department;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.enums.RoleCode;
import com.enterprise.workorder.mapper.DepartmentMapper;
import com.enterprise.workorder.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 审批链解析：把 ticket_type.approval_flow 字符串翻译成"每一级由谁审批"。
 *
 * <p>approval_flow 中每个元素是角色编码，目前支持两种：</p>
 * <ul>
 *   <li><b>DEPT_LEADER</b> —— 申请人所在部门的 leader_id。它不是 RoleCode 枚举值，
 *       而是审批链里的占位符，表示"该部门的主管"</li>
 *   <li><b>ADMIN</b> —— 某个启用的管理员账号</li>
 * </ul>
 *
 * <p>后续若要支持"指定人审批"，在此扩展即可，Service 层无需改动。</p>
 *
 * <p><b>对外只用 {@link #resolveChain}，不要自己逐级拆。</b>
 * 自审规避、去重、兜底这三件事都必须"看到整条链"才能做对 ——
 * 逐级解析在解析第 1 级时根本不知道第 2 级会落到谁头上。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalFlowResolver {

    /** 审批链占位符：部门主管 */
    public static final String PLACEHOLDER_DEPT_LEADER = "DEPT_LEADER";

    /** 审批链占位符：管理员 */
    public static final String PLACEHOLDER_ADMIN = "ADMIN";

    /**
     * 向上追溯部门的层数上限。
     *
     * <p>用于防御脏数据造成的环：若 A 的 parent 是 B、B 的 parent 又是 A，
     * 不加限制的 while 循环会永远转下去，把接口拖死。</p>
     */
    private static final int MAX_DEPARTMENT_DEPTH = 10;

    private final DepartmentMapper departmentMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 解析审批链字符串，返回各级角色编码。
     * 例如 "DEPT_LEADER,ADMIN" -> ["DEPT_LEADER", "ADMIN"]，同时决定审批总级数为 2。
     */
    public List<String> parseFlow(String approvalFlow) {
        if (!StringUtils.hasText(approvalFlow)) {
            return List.of();
        }
        return Arrays.stream(approvalFlow.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 解析出这张工单<b>实际生效</b>的完整审批人链。
     *
     * <p>相比"逐级解析"，它多做三件事，而这三件事都必须看到整条链才能做对：</p>
     *
     * <ol>
     *   <li><b>自审规避</b> —— 某级审批人正是创建人本人时摘掉该级。
     *       典型场景：部门主管自己提交假单，照搬配置的话第 1 级审批人就是他自己，
     *       等于自建自批，审批这个动作失去全部意义。</li>
     *   <li><b>去重</b> —— 同一人不在链上出现两次。否则"部门主管恰好也是管理员"时，
     *       同一个人连审两级，所谓两级审批实际只有一个关卡。</li>
     *   <li><b>兜底</b> —— 整条链被自审规避摘空时（主管提交的单，而链上只有
     *       DEPT_LEADER 一级），回落到其他管理员代审，而不是让单据永远无人可审。</li>
     * </ol>
     *
     * <p><b>为什么摘级而不是直接报错</b>：以"管理员提交请假单（链 DEPT_LEADER,ADMIN）"为例，
     * 第 2 级 ADMIN 会落到他本人。此时正确结果不是拒绝提交，而是让部门主管那一级生效 ——
     * 主管审批才是这张单真正的控制点。同理，主管提交只有一级 DEPT_LEADER 的单时，
     * 由管理员代审。总之目标是"这张单能被审完"，而不是"配置了几级就必须几级"。</p>
     *
     * <p><b>"级数变少"是有意为之，不是 bug</b>：调用方拿返回值的 size 当 totalStep，
     * 所以主管提交的请假单会显示"共 1 级审批"而非配置里的 2 级。被摘掉的那一级
     * 根本不会发生，展示成 2 级只会让申请人一直等一个不会来的审批。</p>
     *
     * <p><b>确定性</b>：只要 typeId / departmentId / creatorId 不变，重复调用结果相同。
     * 因此提交时和每次审批时都可以重新计算，不必把链存进数据库 ——
     * 这顺带解决了"审批到一半部门主管换人"的问题，下一级会自动按新主管派发。</p>
     *
     * @throws BusinessException 审批链为空、某级解析不到人且无法兜底（属于配置错误），
     *                           或整条链都是创建人且找不到他人代审
     */
    public List<Long> resolveChain(String approvalFlow, Long departmentId, Long creatorId) {
        List<String> roles = parseFlow(approvalFlow);
        if (roles.isEmpty()) {
            throw new BusinessException("该工单类型未配置审批链，无法提交");
        }

        List<Long> chain = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            Long candidate = resolveApprover(role, departmentId);
            if (candidate == null) {
                // 解析不到人说明部门主管或管理员账号没配好。
                // 这里刻意不静默塞个默认审批人 —— 那会把配置错误藏起来，
                // 直到某天单据莫名卡住才被发现。但既然是配置问题，报错就要报得能直接改。
                throw new BusinessException(buildUnresolvableMessage(i + 1, role, departmentId));
            }
            if (candidate.equals(creatorId)) {
                log.info("第 {} 级审批人是创建人本人(id={})，按自审规避规则摘掉该级", i + 1, candidate);
                continue;
            }
            if (chain.contains(candidate)) {
                log.info("第 {} 级审批人(id={})已在更早的级次出现，合并重复级", i + 1, candidate);
                continue;
            }
            chain.add(candidate);
        }

        if (chain.isEmpty()) {
            Long substitute = resolveAnyAdminExcluding(creatorId);
            if (substitute == null) {
                throw new BusinessException("这张工单的审批人只能是创建人本人，系统中找不到他人代审。"
                        + "请为该部门另设主管，或再增加一个管理员账号");
            }
            log.info("审批链整条都是创建人本人，回落到管理员(id={})代审", substitute);
            chain.add(substitute);
        }
        return List.copyOf(chain);
    }

    /**
     * 解析某一级的审批人（不考虑自审规避，摘级由 resolveChain 统一处理）。
     *
     * @param roleCode     该级的角色编码
     * @param departmentId 工单所属部门，用于定位部门主管
     * @return 审批人用户ID；解析不到时返回 null，调用方据此判断配置是否有问题
     */
    private Long resolveApprover(String roleCode, Long departmentId) {
        if (PLACEHOLDER_DEPT_LEADER.equals(roleCode)) {
            return resolveDepartmentLeader(departmentId);
        }
        if (PLACEHOLDER_ADMIN.equals(roleCode)) {
            return resolveAnyAdminExcluding(null);
        }
        log.warn("未知的审批链角色编码: {}，该级将无审批人", roleCode);
        return null;
    }

    /**
     * 定位部门主管。
     *
     * <p><b>为什么要沿部门树向上找</b>：只看自己部门的 leader_id，会有一个必然踩到的坑 ——
     * 总公司或某个新建部门没设主管时，该部门所有人提交工单一律被拒，且错误信息只说
     * "请检查部门主管配置"，申请人自己根本改不了这个配置，只能干等管理员。
     * 沿 parent_id 向上找主管，是企业的通行规则（本部门没主管，就由上级部门主管代管），
     * 也是这张单据能真正被审完的最短路径。</p>
     *
     * <p><b>找不到时怎么办</b>：一路问到顶级仍没有主管，就交给管理员兜底 ——
     * 理由同 resolveChain 的兜底：目标是单据能被审完，而不是把它卡在配置上。
     * 但会在日志里留下 WARN，运维可以据此发现"某部门缺主管"。</p>
     *
     * <p><b>循环防护</b>：parent_id 若形成环（A 的上级是 B、B 的上级是 A），
     * while 会变成死循环。用 {@link #MAX_DEPARTMENT_DEPTH} 兜住。</p>
     */
    private Long resolveDepartmentLeader(Long departmentId) {
        if (departmentId == null) {
            log.warn("工单未关联部门，无法解析部门主管，回落到管理员代审");
            return resolveAnyAdminExcluding(null);
        }

        Long currentId = departmentId;
        for (int depth = 0; depth < MAX_DEPARTMENT_DEPTH && currentId != null; depth++) {
            Department department = departmentMapper.selectById(currentId);
            if (department == null) {
                break;
            }
            if (department.getLeaderId() != null) {
                if (depth > 0) {
                    // 发生了上溯，说明原始部门缺主管。这条日志是运维发现配置缺失的入口。
                    log.info("部门 {} 未设置主管，沿部门树上溯至 {} 找到主管(id={})",
                            departmentId, department.getName(), department.getLeaderId());
                }
                return department.getLeaderId();
            }
            // parent_id 为 0 或 null 表示已到顶级，再往上没有了
            Long parentId = department.getParentId();
            currentId = (parentId == null || parentId == 0L) ? null : parentId;
        }

        log.warn("部门 {} 及其所有上级均未设置主管，DEPT_LEADER 级回落到管理员代审", departmentId);
        return resolveAnyAdminExcluding(null);
    }

    /** 解析不到人时给出可直接照做的提示，而不是笼统的"请检查配置" */
    private String buildUnresolvableMessage(int step, String role, Long departmentId) {
        if (PLACEHOLDER_DEPT_LEADER.equals(role)) {
            return "无法确定第 " + step + " 级审批人：部门(id=" + departmentId + ")及其所有上级部门"
                    + "都没有设置主管，并且系统中没有可用的管理员账号。"
                    + "请为该部门设置主管（department.leader_id），或确认至少有一个启用的 ADMIN 账号";
        }
        if (PLACEHOLDER_ADMIN.equals(role)) {
            return "无法确定第 " + step + " 级审批人：系统中没有启用的管理员（ADMIN）账号，"
                    + "请先启用或新建一个管理员账号";
        }
        return "无法确定第 " + step + " 级审批人：审批链里出现了无法识别的角色「" + role
                + "」，请检查 ticket_type.approval_flow 配置";
    }

    /**
     * 取一个可用的管理员。
     *
     * @param excludeUserId 需要排除的用户（创建人本人），可为 null。
     *                      仅仅用于最后的兜底 —— 兜底的目的是"找个别人来审"，
     *                      若不排除创建人，等于绕一圈又把单子还给他自己。
     *                      常规级次解析不排除，因为那里靠 resolveChain 的摘级来处理自审。
     */
    private Long resolveAnyAdminExcluding(Long excludeUserId) {
        SysUser admin = findEnabledAdmin(excludeUserId);
        if (admin == null) {
            log.warn("没有可用的管理员账号（已排除 {}），ADMIN 级审批无法派发", excludeUserId);
            return null;
        }
        return admin.getId();
    }

    /**
     * 按主键升序取第一个启用的管理员。
     *
     * <p>用 id 升序而不是"随便取一个"：让结果稳定可预测。否则同样的工单在不同时刻
     * 可能派给不同管理员，排查问题时会对不上。取 id 最小的那个，等价于"主管理员"。</p>
     */
    private SysUser findEnabledAdmin(Long excludeUserId) {
        List<SysUser> admins = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleCode, RoleCode.ADMIN.name())
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId));
        for (SysUser admin : admins) {
            if (excludeUserId != null && excludeUserId.equals(admin.getId())) {
                continue;
            }
            return admin;
        }
        return null;
    }
}