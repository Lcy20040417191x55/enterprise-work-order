package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.common.ResultCode;
import com.enterprise.workorder.dto.TicketTypeSaveRequest;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.mapper.TicketMapper;
import com.enterprise.workorder.mapper.TicketTypeMapper;
import com.enterprise.workorder.service.ApprovalFlowResolver;
import com.enterprise.workorder.service.TicketTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 工单类型服务实现。
 *
 * <p><b>为什么审批链要在这里校验</b>：{@code approval_flow} 写错了不会有任何即时反馈 ——
 * 直到某天有人提交该类工单才报"审批链配置有误"。管理员那时早已忘记自己改过什么。
 * 在保存这一刻就把每一级角色与 {@link ApprovalFlowResolver} 的占位符常量比对，
 * 错误能立刻定位到具体是第几级写错了。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketTypeServiceImpl implements TicketTypeService {

    private final TicketTypeMapper ticketTypeMapper;
    private final TicketMapper ticketMapper;

    @Override
    public List<TicketType> listEnabled() {
        return ticketTypeMapper.selectList(new LambdaQueryWrapper<TicketType>()
                .eq(TicketType::getEnabled, 1)
                .orderByAsc(TicketType::getSort)
                .orderByAsc(TicketType::getId));
    }

    @Override
    public List<TicketType> listAll() {
        return ticketTypeMapper.selectList(new LambdaQueryWrapper<TicketType>()
                .orderByAsc(TicketType::getSort)
                .orderByAsc(TicketType::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketType create(TicketTypeSaveRequest request) {
        String code = normalizeCode(request.getCode());
        requireCodeNotUsed(code, null);

        TicketType type = new TicketType();
        applyTo(type, request, code);
        ticketTypeMapper.insert(type);

        log.info("工单类型已创建: {} ({})", type.getName(), type.getCode());
        return type;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketType update(Long id, TicketTypeSaveRequest request) {
        TicketType type = id == null ? null : ticketTypeMapper.selectById(id);
        if (type == null) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "工单类型不存在");
        }

        String code = normalizeCode(request.getCode());
        requireCodeNotUsed(code, id);

        // 审批链变短时，正在审批中的工单可能在下一级取不到人。
        // 这里不阻止修改（管理员有权调整流程），但要留下可追溯的日志：
        // 真出了"第 3 级不存在"的报错，能从这里查到是谁在什么时候改了配置。
        String oldFlow = type.getApprovalFlow();
        applyTo(type, request, code);
        ticketTypeMapper.updateById(type);

        if (!StringUtils.hasText(oldFlow) || !oldFlow.equals(type.getApprovalFlow())) {
            log.warn("工单类型 {} 的审批链已变更: [{}] -> [{}]，审批中的工单将在下一级按新链派发",
                    type.getCode(), oldFlow, type.getApprovalFlow());
        }
        log.info("工单类型已修改: {} ({})", type.getName(), type.getCode());
        return type;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        TicketType type = id == null ? null : ticketTypeMapper.selectById(id);
        if (type == null) {
            throw new BusinessException(ResultCode.NOT_FOUND,
                    "工单类型不存在");
        }

        // 已被引用的类型不能删。
        // 理由：工单表只存 type_id，若类型被删，历史工单的"这是什么类型的单"就永久丢失，
        // 列表页的类型名会变成空白，审批链也再也算不出来（会影响在途单据的后续级次）。
        // 若确实不想再让人新建这类工单，正确做法是"停用"而不是"删除"。
        Long used = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTypeId, id));
        if (used != null && used > 0) {
            throw new BusinessException("该类型已被 " + used + " 张工单使用，不能删除。"
                    + "若只是不想再新建这类工单，请改为「停用」");
        }

        ticketTypeMapper.deleteById(id);
        log.info("工单类型已删除: {} ({})", type.getName(), type.getCode());
    }

    // ==================================================================
    //  内部方法
    // ==================================================================

    /** 写入实体。编码由调用方归一化后传入 */
    private void applyTo(TicketType type, TicketTypeSaveRequest request, String code) {
        type.setCode(code);
        type.setName(request.getName().trim());
        type.setDescription(StringUtils.hasText(request.getDescription())
                ? request.getDescription().trim() : null);
        type.setApprovalFlow(normalizeFlow(request.getApprovalFlow()));
        type.setSort(request.getSort() == null ? 0 : request.getSort());
        // null 视为启用：新建时不传 enabled 是最常见的用法，不该因此变成停用状态
        type.setEnabled(request.getEnabled() == null ? 1 : request.getEnabled());
    }

    /**
     * 归一化编码：去空白 + 转大写。
     *
     * <p>为什么统一转大写而不是原样存：管理员输入 "leave" 与 "LEAVE" 语义上就是同一个类型，
     * 若原样存下两种写法，后续按 code 精确查询会漏。既然 @Pattern 已限定只能是
     * 大写字母/数字/下划线，这里做一次转换不会掩盖真正的格式错误。</p>
     */
    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 归一化审批链，并逐级校验角色合法性。
     *
     * <p>拼写错误在这里就被拦住，而不是等到有人提交该类工单时才暴露。</p>
     */
    private String normalizeFlow(String approvalFlow) {
        List<String> roles = parseRoles(approvalFlow);
        if (roles.isEmpty()) {
            throw new BusinessException("审批链不能为空，至少配置一级");
        }
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            boolean known = ApprovalFlowResolver.PLACEHOLDER_DEPT_LEADER.equals(role)
                    || ApprovalFlowResolver.PLACEHOLDER_ADMIN.equals(role);
            if (!known) {
                throw new BusinessException("审批链第 " + (i + 1) + " 级「" + role + "」不是合法的角色，"
                        + "可选值为 " + ApprovalFlowResolver.PLACEHOLDER_DEPT_LEADER
                        + " / " + ApprovalFlowResolver.PLACEHOLDER_ADMIN);
            }
        }
        return String.join(",", roles);
    }

    private List<String> parseRoles(String approvalFlow) {
        return StringUtils.hasText(approvalFlow)
                ? java.util.Arrays.stream(approvalFlow.split(","))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .map(s -> s.toUpperCase(Locale.ROOT))
                        .toList()
                : List.of();
    }

    /**
     * 校验编码未被占用。
     *
     * <p>先查再插会有一点并发窗口（两个管理员同时建同一个 code），
     * 但 department/sys_user 上的唯一索引才是最终防线：真撞了会抛
     * DuplicateKeyException，不会写进脏数据。这里提前查一次是为了给出
     * "编码已存在"这种能看懂的错误，而不是把数据库异常抛给前端。</p>
     *
     * @param excludeId 修改场景下排除自己，否则"只改名称不改编码"必然报重复
     */
    private void requireCodeNotUsed(String code, Long excludeId) {
        LambdaQueryWrapper<TicketType> wrapper = new LambdaQueryWrapper<TicketType>()
                .eq(TicketType::getCode, code);
        if (excludeId != null) {
            wrapper.ne(TicketType::getId, excludeId);
        }
        if (ticketTypeMapper.exists(wrapper)) {
            throw new BusinessException("类型编码「" + code + "」已存在");
        }
    }
}
