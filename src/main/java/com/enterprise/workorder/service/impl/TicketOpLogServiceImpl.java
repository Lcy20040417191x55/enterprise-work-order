package com.enterprise.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.workorder.entity.SysUser;
import com.enterprise.workorder.entity.TicketOpLog;
import com.enterprise.workorder.enums.TicketOpAction;
import com.enterprise.workorder.mapper.TicketOpLogMapper;
import com.enterprise.workorder.security.LoginUser;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.TicketOpLogService;
import com.enterprise.workorder.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 工单操作日志实现。
 *
 * <p>本类只做一件事：把"谁、什么时候、对哪张单、做了什么"准确地写进表里。
 * 它不判断权限、不校验状态 —— 那些是调用方（{@code TicketServiceImpl} 等）的职责，
 * 本类被调用时，业务动作已经通过了全部校验。这个边界必须守清楚：
 * 若在这里再判断一次状态，就会出现"日志服务认为该记、业务服务认为不该记"的分歧，
 * 排查时两边都觉得自己是对的。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketOpLogServiceImpl implements TicketOpLogService {

    /** 与 ticket_op_log.detail 的列宽一致 */
    private static final int DETAIL_MAX = 500;

    /** 系统动作的操作人显示名。用中文常量而不是散落的字面量，避免出现"系统"/"System"两种写法 */
    private static final String SYSTEM_OPERATOR = "系统";

    private final TicketOpLogMapper opLogMapper;
    private final UserService userService;

    @Override
    public void record(Long ticketId, TicketOpAction action, String detail) {
        if (ticketId == null || action == null) {
            // 调用方传空说明代码写错了。这里记一条 warn 后返回，
            // 而不是抛异常：日志组件自己不该成为业务失败的原因，
            // 而这条 warn 足以让开发期发现问题。
            log.warn("操作日志参数不完整，已跳过: ticketId={} action={}", ticketId, action);
            return;
        }

        TicketOpLog row = new TicketOpLog();
        row.setTicketId(ticketId);
        row.setAction(action.name());
        row.setDetail(truncate(detail, DETAIL_MAX));

        LoginUser current = SecurityUtils.getLoginUserOrNull();
        if (current == null) {
            // 没有登录人 = 系统动作。operatorId 留 null，不编造用户。
            row.setOperatorName(SYSTEM_OPERATOR);
        } else {
            row.setOperatorId(current.getUserId());
            row.setOperatorName(displayName(current));
        }

        opLogMapper.insert(row);
    }

    @Override
    public LocalDateTime lastUrgeAt(Long ticketId) {
        if (ticketId == null) {
            return null;
        }
        // 只取一条：催办记录通常每张单只有寥寥几条，按 id 倒序取第一条即可，
        // 比 MAX(created_at) 更好的地方是它同时能命中 idx_ticket(ticket_id, id) 的有序前缀。
        TicketOpLog latest = opLogMapper.selectOne(new LambdaQueryWrapper<TicketOpLog>()
                .eq(TicketOpLog::getTicketId, ticketId)
                .eq(TicketOpLog::getAction, TicketOpAction.URGE.name())
                .orderByDesc(TicketOpLog::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getCreatedAt();
    }

    /**
     * 取操作人姓名快照。
     *
     * <p>与 {@code TicketServiceImpl#displayName} 同样的优先级：
     * 先用 sys_user.real_name，查不到（用户被逻辑删除、脏外键）时回落到登录名，
     * 最差也不会把 null 写进 NOT NULL 列导致整个业务事务失败。</p>
     */
    private String displayName(LoginUser current) {
        SysUser user = userService.getById(current.getUserId());
        if (user != null && user.getRealName() != null && !user.getRealName().isBlank()) {
            return user.getRealName();
        }
        return current.getUsername();
    }

    /**
     * 按字符数截断。
     *
     * <p>截断而不是抛错：日志的 detail 是"补充说明"，用户传的评论/文件名可能很长，
     * 若因为说明文字超长就让整个业务动作失败，等于让附属信息反过来否决主流程。</p>
     *
     * <p>按 {@code String.length()} 而不是字节数：数据库列以字符计量
     * （utf8mb4 下 VARCHAR(500) 就是 500 个字符），中文不会被算成 3 个长度。
     * 这一段的逻辑与 {@code NotificationServiceImpl#truncate} 相同，
     * 刻意没有抽公共方法：两处各自独立、语义上随时可能分叉
     * （例如日志将来允许保留 1000 字、通知仍限 500），
     * 为一个 4 行的方法多加一层公共类，收益不抵它带来的耦合。</p>
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}