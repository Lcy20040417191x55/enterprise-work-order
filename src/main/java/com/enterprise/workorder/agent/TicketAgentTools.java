package com.enterprise.workorder.agent;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.workorder.dto.TicketOpLogVO;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.enums.ApprovalAction;
import com.enterprise.workorder.enums.Priority;
import com.enterprise.workorder.security.SecurityUtils;
import com.enterprise.workorder.service.TicketService;
import com.enterprise.workorder.service.TicketTypeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 提供给大模型调用的工具集。
 *
 * <p><b>这个类是整个 Agent 功能里权限风险最高的地方，原因有两条：</b></p>
 *
 * <ol>
 *   <li><b>身份必须取自令牌，不能取自对话内容。</b>
 *       工具执行时通过 {@link SecurityUtils#getLoginUser()} 拿当前登录人，
 *       与普通接口走的是同一条路径。绝不允许把「我是谁」作为工具参数由模型填写 ——
 *       历史消息是前端传来的，可以被伪造；一旦身份能从对话里传进来，
 *       问一句「帮我看看总经理的单」就能越权读到别人的数据。</li>
 *   <li><b>查询必须复用 TicketService 的可见性规则。</b>
 *       本类不直接查 Mapper，而是调 {@code TicketService.page()}，
 *       因为「哪些单子这个人能看」的规则只应该有一份实现。
 *       若这里自己写一个 SQL，将来工单加了「协作人」字段，
 *       普通接口收紧了范围而 Agent 这条路径没有 —— 而这条路径没有人会去回归测试。</li>
 * </ol>
 *
 * <p><b>为什么工具方法返回的是 JSON 字符串</b>：工具执行结果要回灌给模型，
 * 而 OpenAI 兼容协议里 tool 消息的 content 就是字符串。在这里直接序列化，
 * 而不是让 AgentService 去判断「这个返回值该转成什么」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAgentTools {

    /** 工具名常量。提示词、schema、分发逻辑都引用这里，避免三处字符串各写一遍 */
    public static final String TOOL_LIST_TYPES = "listTicketTypes";
    public static final String TOOL_QUERY_TICKETS = "queryMyTickets";
    public static final String TOOL_DRAFT_TICKET = "draftTicket";

    /** 按单号或标题定位一张可见工单，返回详情、审批轨迹与操作时间线 */
    public static final String TOOL_GET_TICKET_DETAIL = "getTicketDetail";

    /** 一次查询最多返回多少条，防止模型一次拉回几百条把上下文撑爆 */
    private static final int QUERY_LIMIT = 20;

    /**
     * 「能看哪些单」等价于这两个 scope 的并集：我发起的 ∪ 待我审批 ∪ 我审批过的。
     *
     * <p>把它写成常量而不是在方法里各写一次字面量，是因为它同时承担一个安全断言：
     * 这两个 scope 的结果都不会超出 {@code TicketService} 的可见范围，
     * 因此按它们搜索不会打开任何新的权限口子。改动这里就等于改动可见性边界，
     * 不该让人在两个字面量之间去找。</p>
     */
    private static final List<String> VISIBLE_SCOPES = List.of("all", "done");

    /** 审批轨迹与操作时间线各保留最近多少条。老单可能有上百条，全给会淹没重点、白烧 token */
    private static final int TRAIL_LIMIT = 8;

    private final TicketService ticketService;
    private final TicketTypeService ticketTypeService;
    private final ObjectMapper objectMapper;

    /**
     * 工具说明清单，直接交给模型。
     *
     * <p>description 写的是「什么时候该用它」，而不是「它做了什么」。
     * 模型选择工具靠的是场景匹配，把「用户在描述一个待办事项时调用」
     * 这种触发条件写清楚，比描述参数结构更能减少误调用。</p>
     *
     * <p>三个 schema 刻意写成紧凑的单行 JSON 字符串常量，不引入额外的资源文件 ——
     * schema 与执行代码必须同步演进，分开放会让人改了方法忘了改 schema。</p>
     */
    public List<LlmClient.ToolSpec> specs() {
        return List.of(
                new LlmClient.ToolSpec(
                        TOOL_LIST_TYPES,
                        "列出当前系统里启用的全部工单类型及其编码。"
                                + "当你不确定该把用户的需求归到哪个类型时调用它。无需参数。",
                        "{\"type\":\"object\",\"properties\":{},\"required\":[]}"),
                new LlmClient.ToolSpec(
                        TOOL_QUERY_TICKETS,
                        "查询当前登录用户可见的工单。"
                                + "当用户询问自己的工单进展、某张单审批到哪一步、有哪些待办时调用。"
                                + "只能查到该用户有权查看的工单，无法查询他人数据。",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"scope\":{\"type\":\"string\",\"enum\":[\"mine\",\"todo\",\"done\",\"all\"],"
                                + "\"description\":\"mine=我发起的；todo=我的待办；done=我已审批过的；"
                                + "all=全部（仅管理员为全量，其他人自动收窄为与我相关的）\"},"
                                + "\"keyword\":{\"type\":\"string\",\"description\":\"按标题或单号模糊匹配，可不传\"},"
                                + "\"status\":{\"type\":\"string\","
                                + "\"enum\":[\"DRAFT\",\"PENDING\",\"APPROVED\",\"REJECTED\",\"WITHDRAWN\",\"CLOSED\"],"
                                + "\"description\":\"按状态过滤，可不传\"}"
                                + "},\"required\":[\"scope\"]}"),
                new LlmClient.ToolSpec(
                        TOOL_DRAFT_TICKET,
                        "把用户的自然语言描述整理成一张工单草稿。"
                                + "当用户在描述一件要办的事、要求提单、报修、申请时调用。"
                                + "注意：本工具只生成草稿并返回给用户确认，不会真的提交工单，"
                                + "因此不要向用户承诺已经提交，应说明已为你填好、请确认后提交。",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"typeCode\":{\"type\":\"string\","
                                + "\"description\":\"工单类型编码，取值必须来自 listTicketTypes 的结果\"},"
                                + "\"title\":{\"type\":\"string\","
                                + "\"description\":\"一句话概括要办的事，不超过40字，直接写事项，不要以申请二字开头\"},"
                                + "\"content\":{\"type\":\"string\","
                                + "\"description\":\"把用户的原始描述整理成通顺的说明，保留时间、地点、影响范围等关键信息\"},"
                                + "\"priority\":{\"type\":\"string\",\"enum\":[\"LOW\",\"NORMAL\",\"HIGH\",\"URGENT\"],"
                                + "\"description\":\"紧急程度。只有明确出现急、尽快、影响工作等表述时才用HIGH或URGENT，"
                                + "否则用NORMAL\"},"
                                + "\"confidence\":{\"type\":\"number\",\"description\":\"对类型判断的把握，0到1之间\"},"
                                + "\"reasoning\":{\"type\":\"string\","
                                + "\"description\":\"一句话说明为什么选这个类型和优先级，给用户看\"}"
                                + "},\"required\":[\"typeCode\",\"title\",\"content\",\"priority\"]}"),
                new LlmClient.ToolSpec(
                        TOOL_GET_TICKET_DETAIL,
                        "查看某一张工单的详细信息，包括审批轨迹与操作时间线。"
                                + "当用户要了解某张单的进展、为什么被驳回、现在卡在谁那里、"
                                + "或者提到「那张单」「我上次提的」这类指向一张具体工单的说法时调用它。"
                                + "只能查看当前用户有权查看的工单；查不到就如实说明，不要编造。",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"ticketNo\":{\"type\":\"string\","
                                + "\"description\":\"工单号，例如 TK20260923-0001\"},"
                                + "\"keyword\":{\"type\":\"string\","
                                + "\"description\":\"按标题或单号模糊匹配，用于只知道大致内容的场景\"}"
                                + "},\"required\":[]}"));
    }

    /**
     * 执行一次工具调用。
     *
     * <p><b>任何异常都在这里被吞掉并转成 JSON 里的错误字段</b>，而不是抛出去。
     * 理由：工具执行失败不该让整轮对话失败。把「这个查询超时了」作为工具结果回灌给模型，
     * 它还能换一种方式回答用户；若直接抛异常，用户得到的是一句报错，
     * 连模型已经想好的那部分回答都拿不到。</p>
     *
     * @return 回灌给模型的 JSON 字符串
     */
    public String execute(String name, String argumentsJson) {
        try {
            return switch (name) {
                case TOOL_LIST_TYPES -> listTypes();
                case TOOL_QUERY_TICKETS -> queryTickets(argumentsJson);
                case TOOL_DRAFT_TICKET -> draftTicket(argumentsJson);
                case TOOL_GET_TICKET_DETAIL -> getTicketDetail(argumentsJson);
                default -> error("未知工具: " + name);
            };
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", name, e.getMessage());
            return error("工具执行失败: " + e.getMessage());
        }
    }

    /** 解析建单意图的结果，供 AgentService 装配成响应。null 表示这一轮没有建单 */
    public TicketDraft parseDraft(String toolResultJson) {
        try {
            Map<?, ?> map = objectMapper.readValue(toolResultJson, Map.class);
            if (map.containsKey("error")) {
                return null;
            }
            Object typeId = map.get("typeId");
            return new TicketDraft(
                    typeId instanceof Number n ? n.longValue() : null,
                    str(map.get("typeName"), null),
                    str(map.get("title"), null),
                    str(map.get("content"), null),
                    str(map.get("priority"), null),
                    num(map.get("confidence")),
                    str(map.get("reasoning"), null),
                    str(map.get("warning"), null));
        } catch (Exception e) {
            log.warn("草稿解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 定位并读取一张工单的详情、审批轨迹与操作时间线。
     *
     * <p><b>为什么需要「按单号/标题定位」，而不是让模型直接给 ticketId</b>：
     * 模型手里没有 id。用户说的是「我那张打印机维修的单」，它不可能凭空知道那是 689 号；
     * 若硬要它输出 id，它只会编一个，然后拿回来的要么是别人的数据、要么是一句报错。
     * 让工具自己去查、模型只负责说「我要找哪张单」，才是它真正擅长的分工。</p>
     *
     * <p><b>搜索范围限定在 {@link #VISIBLE_SCOPES}，不新增权限面</b>：
     * 这两个 scope 的结果都不会超出 {@code TicketService} 的可见范围，
     * 因此能在本工具里查到的单，就一定能打开它的详情页 —— 反之查不到，就是用户确实没权限。</p>
     *
     * <p><b>审批轨迹与时间线都做条数截断</b>：一张反复流转的老单可能有上百条记录，
     * 全量塞进上下文既烧 token 又淹没重点；而「为什么被驳回」「现在卡在谁那里」
     * 这两个最常被问的问题，答案都在最近的动作里。</p>
     */
    private String getTicketDetail(String argumentsJson) throws Exception {
        Map<?, ?> args = readArgs(argumentsJson);
        String ticketNo = blankToNull(str(args.get("ticketNo"), null));
        String keyword = blankToNull(str(args.get("keyword"), null));

        if (ticketNo == null && keyword == null) {
            return error("请提供工单号，或用一句话描述要查的那张单");
        }

        TicketVO target = locateVisibleTicket(ticketNo, keyword);
        if (target == null) {
            // 刻意不区分「不存在」与「无权查看」：两者回同一句话，
            // 否则这个工具会变成探测他人工单是否存在的接口
            return error("没有找到你有权查看的工单：" + (ticketNo != null ? ticketNo : keyword));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketNo", target.getTicketNo());
        result.put("title", target.getTitle());
        result.put("content", target.getContent());
        result.put("typeName", target.getTypeName());
        result.put("priorityLabel", target.getPriorityLabel());
        result.put("status", target.getStatus());
        result.put("statusLabel", target.getStatusLabel());
        result.put("creatorName", target.getCreatorName());
        result.put("departmentName", target.getDepartmentName());
        result.put("currentStep", target.getCurrentStep());
        result.put("totalStep", target.getTotalStep());
        result.put("currentApproverName", target.getCurrentApproverName());
        result.put("submittedAt", target.getSubmittedAt() == null ? null : target.getSubmittedAt().toString());
        result.put("finishedAt", target.getFinishedAt() == null ? null : target.getFinishedAt().toString());

        // 轨迹与时间线都走 Service：可见性校验在这里再做一次，且规则只有一份实现
        result.put("approvalTrail", summarizeHistory(ticketService.history(target.getId())));
        result.put("opTimeline", summarizeOpLogs(ticketService.opLogs(target.getId())));

        result.put("url", "/#/tickets/" + target.getId());
        return write(result);
    }

    /**
     * 在可见范围内定位一张单。
     *
     * <p>先按单号精确找，找不到再退回模糊找 —— 单号唯一，命中即确定；
     * 用户只记得标题时才有必要模糊搜，而模糊搜可能命中多张，取第一条并让模型自行取舍。</p>
     */
    private TicketVO locateVisibleTicket(String ticketNo, String keyword) {
        if (ticketNo != null) {
            TicketVO exact = firstMatch(ticketNo, true);
            if (exact != null) {
                return exact;
            }
        }
        return firstMatch(keyword != null ? keyword : ticketNo, false);
    }

    /**
     * 遍历可见 scope 取第一条命中。
     *
     * @param exactTicketNo true 时只接受单号完全相等的记录（用于精确查找）
     */
    private TicketVO firstMatch(String searchTerm, boolean exactTicketNo) {
        for (String scope : VISIBLE_SCOPES) {
            TicketQuery query = new TicketQuery();
            query.setScope(scope);
            query.setKeyword(searchTerm);
            query.setPageNum(1);
            query.setPageSize(QUERY_LIMIT);
            for (TicketVO vo : ticketService.page(query).getRecords()) {
                if (!exactTicketNo || searchTerm.equalsIgnoreCase(vo.getTicketNo())) {
                    return vo;
                }
            }
        }
        return null;
    }

    /** 审批轨迹摘要，只留最近 {@link #TRAIL_LIMIT} 条 */
    private List<Map<String, Object>> summarizeHistory(List<ApprovalRecord> history) {
        return history.stream()
                .skip(Math.max(0, history.size() - TRAIL_LIMIT))
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("step", r.getStep());
                    item.put("approverName", r.getApproverName());
                    item.put("actionLabel", approvalLabel(r.getAction()));
                    item.put("comment", r.getComment());
                    item.put("at", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
                    return item;
                })
                .toList();
    }

    /** 操作时间线摘要，只留最近 {@link #TRAIL_LIMIT} 条 */
    private List<Map<String, Object>> summarizeOpLogs(List<TicketOpLogVO> logs) {
        return logs.stream()
                .skip(Math.max(0, logs.size() - TRAIL_LIMIT))
                .map(log -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("actionLabel", log.getActionLabel());
                    item.put("operatorName", log.getOperatorName());
                    item.put("detail", log.getDetail());
                    item.put("at", log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
                    return item;
                })
                .toList();
    }

    /** 审批动作的中文名；脏数据原样返回，避免展示层因未知取值而报错 */
    private static String approvalLabel(String action) {
        try {
            return ApprovalAction.valueOf(action).getLabel();
        } catch (IllegalArgumentException | NullPointerException e) {
            return action;
        }
    }

    private String listTypes() {
        List<TicketType> types = ticketTypeService.listEnabled();
        List<Map<String, Object>> items = types.stream()
                .map(t -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", t.getCode());
                    item.put("name", t.getName());
                    item.put("description", t.getDescription());
                    return item;
                })
                .toList();
        return write(Map.of("types", items));
    }

    private String queryTickets(String argumentsJson) throws Exception {
        Map<?, ?> args = readArgs(argumentsJson);

        TicketQuery query = new TicketQuery();
        query.setScope(str(args.get("scope"), "mine"));
        query.setKeyword(blankToNull(str(args.get("keyword"), null)));
        query.setStatus(blankToNull(str(args.get("status"), null)));
        query.setPageNum(1);
        query.setPageSize(QUERY_LIMIT);

        // 走 Service 而不是 Mapper：可见性规则（谁能看哪些单）只有一份实现
        IPage<TicketVO> page = ticketService.page(query);

        List<Map<String, Object>> items = page.getRecords().stream()
                .map(this::summarize)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("returned", items.size());
        result.put("tickets", items);
        if (page.getTotal() > items.size()) {
            result.put("note", "结果较多，只返回前 " + QUERY_LIMIT + " 条");
        }
        return write(result);
    }

    /** 只回传模型回答所需的最小字段集，避免把整行数据塞进上下文白烧 token */
    private Map<String, Object> summarize(TicketVO vo) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ticketNo", vo.getTicketNo());
        item.put("title", vo.getTitle());
        item.put("typeName", vo.getTypeName());
        item.put("status", vo.getStatus());
        item.put("statusLabel", vo.getStatusLabel());
        item.put("priorityLabel", vo.getPriorityLabel());
        item.put("currentStep", vo.getCurrentStep());
        item.put("totalStep", vo.getTotalStep());
        item.put("currentApproverName", vo.getCurrentApproverName());
        item.put("submittedAt", vo.getSubmittedAt() == null ? null : vo.getSubmittedAt().toString());
        item.put("url", "/#/tickets/" + vo.getId());
        return item;
    }

    /**
     * 解析建单意图。
     *
     * <p><b>两处刻意不信任模型输出</b>：</p>
     * <ol>
     *   <li>typeCode 必须在启用类型里查得到。模型可能编出 IT_SUPPORT 这种不存在的编码，
     *       直接拿它建单会得到一个外键不存在或类型已停用的脏数据。</li>
     *   <li>priority 必须落在枚举范围内。取值非法时回落到 NORMAL，
     *       而不是猜一个 —— 把不急的单标成 URGENT 会浪费审批人的注意力。</li>
     * </ol>
     *
     * <p>官方文档也明确了这一点：模型生成的参数可能是非法 JSON，也可能包含 schema 里
     * 没有定义的字段，调用方必须自行校验。所以这里对每一个字段都做了兜底。</p>
     */
    private String draftTicket(String argumentsJson) throws Exception {
        Map<?, ?> args = readArgs(argumentsJson);

        String title = str(args.get("title"), "").trim();
        String content = str(args.get("content"), "").trim();
        if (!StringUtils.hasText(title)) {
            // 标题为空时用内容首句兜底，而不是直接失败 —— 用户至少能看到表单里有点东西
            title = content.length() > 30 ? content.substring(0, 30) : content;
        }
        if (!StringUtils.hasText(title)) {
            return error("标题与内容均为空，无法生成草稿");
        }
        if (title.length() > 128) {
            title = title.substring(0, 128);
        }

        String priority = normalizePriority(str(args.get("priority"), null));

        String rawCode = str(args.get("typeCode"), "").trim();
        TicketType matched = matchType(rawCode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeId", matched == null ? null : matched.getId());
        result.put("typeCode", matched == null ? null : matched.getCode());
        result.put("typeName", matched == null ? null : matched.getName());
        result.put("title", title);
        result.put("content", content);
        result.put("priority", priority);
        result.put("confidence", num(args.get("confidence")));

        String warning = null;
        if (matched == null) {
            // 不替用户猜类型：选错类型意味着走错审批链，比让他多点一下严重得多
            warning = StringUtils.hasText(rawCode)
                    ? "未能匹配到类型「" + rawCode + "」，请手动选择"
                    : "未能确定工单类型，请手动选择";
            result.put("reasoning", "已整理好标题、内容与优先级，类型需要你确认");
        } else {
            result.put("reasoning", str(args.get("reasoning"), null));
        }
        result.put("warning", warning);
        return write(result);
    }

    /** 按编码找启用类型；编码大小写与空格差异都容忍 */
    private TicketType matchType(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (TicketType type : ticketTypeService.listEnabled()) {
            if (type.getCode() != null && type.getCode().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    private String normalizePriority(String raw) {
        if (StringUtils.hasText(raw)) {
            try {
                return Priority.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException ignored) {
                // 落到下面返回默认值
            }
        }
        return Priority.NORMAL.name();
    }

    private Map<?, ?> readArgs(String argumentsJson) throws Exception {
        if (!StringUtils.hasText(argumentsJson)) {
            return Map.of();
        }
        return objectMapper.readValue(argumentsJson, Map.class);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    private String error(String message) {
        return write(Map.of("error", message));
    }

    private static String str(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Double num(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}