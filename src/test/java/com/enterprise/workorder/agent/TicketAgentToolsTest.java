package com.enterprise.workorder.agent;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.dto.TicketOpLogVO;
import com.enterprise.workorder.entity.ApprovalRecord;
import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.service.TicketService;
import com.enterprise.workorder.service.TicketTypeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具集的测试。
 *
 * <p><b>这一组用例守护的是「不信任模型输出」这条原则。</b>
 * 模型返回的参数是自由文本，官方文档明确说它可能生成非法 JSON、
 * 也可能带上 schema 里根本没定义的字段。若代码直接采信，症状会是
 * 「某天某张单莫名其妙挂到了不存在的类型上」，而且只在模型抽风时出现，极难复现。</p>
 *
 * <p>因此这里逐条钉住四种兜底：类型编码查不到、优先级取值非法、
 * 标题为空、参数根本不是 JSON。它们都不该抛异常，而应回传一个带说明的结果。</p>
 *
 * <p>不连数据库：依赖全用 Mockito 替身。工具本身的逻辑是「解析 + 校验」，
 * 与数据库无关，连库只会让这组用例变慢且受开发数据影响。</p>
 */
@DisplayName("智能助手 - 工具集")
class TicketAgentToolsTest {

    private TicketService ticketService;
    private TicketTypeService ticketTypeService;
    private TicketAgentTools tools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        ticketTypeService = mock(TicketTypeService.class);
        tools = new TicketAgentTools(ticketService, ticketTypeService, objectMapper);
    }

    private TicketType type(long id, String code, String name) {
        TicketType t = new TicketType();
        t.setId(id);
        t.setCode(code);
        t.setName(name);
        t.setEnabled(1);
        return t;
    }

    @Nested
    @DisplayName("建单草稿")
    class Draft {

        @BeforeEach
        void types() {
            when(ticketTypeService.listEnabled())
                    .thenReturn(List.of(type(1L, "IT_SUPPORT", "IT支持"), type(2L, "LEAVE", "请假申请")));
        }

        @Test
        @DisplayName("类型编码正确时给出 typeId，且不再提示需人工选择")
        void matchesTypeCode() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"IT_SUPPORT","title":"三楼打印机故障","content":"三楼打印机坏了，急用","priority":"HIGH"}
                    """);

            TicketDraft draft = tools.parseDraft(json);
            assertThat(draft).isNotNull();
            assertThat(draft.typeId()).isEqualTo(1L);
            assertThat(draft.typeName()).isEqualTo("IT支持");
            assertThat(draft.priority()).isEqualTo("HIGH");
            assertThat(draft.warning()).isNull();
        }

        @Test
        @DisplayName("类型编码大小写与空格不同也应匹配上")
        void matchesTypeCodeIgnoringCase() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"  it_support  ","title":"打印机","content":"坏了","priority":"NORMAL"}
                    """);

            TicketDraft draft = tools.parseDraft(json);
            assertThat(draft).isNotNull();
            assertThat(draft.typeId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("模型编造了不存在的类型编码时，草稿仍生成，但带 warning 且 typeId 为空")
        void unknownTypeCodeFallsBack() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"NOT_EXIST","title":"打印机","content":"坏了","priority":"NORMAL"}
                    """);

            TicketDraft draft = tools.parseDraft(json);
            assertThat(draft).isNotNull();
            // 不替用户猜类型：选错类型意味着走错审批链
            assertThat(draft.typeId()).isNull();
            assertThat(draft.warning()).contains("NOT_EXIST");
            // 其余字段仍要保住，用户不至于从零开始填
            assertThat(draft.title()).isEqualTo("打印机");
            assertThat(draft.content()).isEqualTo("坏了");
            assertThat(draft.priority()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("优先级取值非法时回落为 NORMAL，而不是把不急的单标成 URGENT")
        void invalidPriorityFallsBackToNormal() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"IT_SUPPORT","title":"打印机","content":"坏了","priority":"VERY_URGENT"}
                    """);

            TicketDraft draft = tools.parseDraft(json);
            assertThat(draft).isNotNull();
            assertThat(draft.priority()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("优先级缺省时同样回落为 NORMAL")
        void missingPriorityFallsBackToNormal() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"IT_SUPPORT","title":"打印机","content":"坏了"}
                    """);

            assertThat(tools.parseDraft(json).priority()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("模型没给标题时用内容首句兜底，而不是直接失败")
        void titleFallsBackToContent() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"IT_SUPPORT","title":"","content":"三楼打印机坏了需要维修","priority":"NORMAL"}
                    """);

            TicketDraft draft = tools.parseDraft(json);
            assertThat(draft).isNotNull();
            assertThat(draft.title()).isEqualTo("三楼打印机坏了需要维修");
        }

        @Test
        @DisplayName("标题超长时截断到 128 字，避免超出数据库字段长度")
        void titleTruncated() {
            String longTitle = "打".repeat(200);
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET,
                    "{\"typeCode\":\"IT_SUPPORT\",\"title\":\"" + longTitle + "\","
                            + "\"content\":\"x\",\"priority\":\"NORMAL\"}");

            assertThat(tools.parseDraft(json).title()).hasSize(128);
        }

        @Test
        @DisplayName("标题与内容都为空时不产出草稿，回传错误说明")
        void emptyTitleAndContent() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, """
                    {"typeCode":"IT_SUPPORT","title":"","content":"","priority":"NORMAL"}
                    """);

            assertThat(tools.parseDraft(json)).isNull();
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("参数不是合法 JSON 时不抛异常，回传错误说明让模型自行改口")
        void malformedArgumentsDoNotThrow() {
            String json = tools.execute(TicketAgentTools.TOOL_DRAFT_TICKET, "{这不是 JSON");

            assertThat(json).contains("error");
            assertThat(tools.parseDraft(json)).isNull();
        }
    }

    @Nested
    @DisplayName("工单查询")
    class Query {

        @Test
        @DisplayName("查询走 TicketService，可见性规则不在这里另写一份")
        void delegatesToService() {
            Page<TicketVO> page = new Page<>(1, 20);
            page.setTotal(1);
            TicketVO vo = new TicketVO();
            vo.setId(9L);
            vo.setTicketNo("WO202609220001");
            vo.setTitle("打印机故障");
            vo.setStatusLabel("审批中");
            vo.setCurrentStep(1);
            vo.setTotalStep(2);
            page.setRecords(List.of(vo));
            when(ticketService.page(any(TicketQuery.class))).thenReturn(page);

            String json = tools.execute(TicketAgentTools.TOOL_QUERY_TICKETS, """
                    {"scope":"mine"}
                    """);

            assertThat(json).contains("WO202609220001").contains("审批中");
            // 关键断言：确实调用了 Service，而不是自己拼 SQL
            ArgumentCaptor<TicketQuery> captor = ArgumentCaptor.forClass(TicketQuery.class);
            verify(ticketService).page(captor.capture());
            assertThat(captor.getValue().getScope()).isEqualTo("mine");
            // 一次最多带回 20 条，防止把上下文撑爆
            assertThat(captor.getValue().getPageSize()).isLessThanOrEqualTo(20);
        }

        @Test
        @DisplayName("scope 缺省时按我发起的查，而不是查全部")
        void defaultScopeIsMine() {
            Page<TicketVO> empty = new Page<>(1, 20);
            empty.setRecords(List.of());
            when(ticketService.page(any(TicketQuery.class))).thenReturn(empty);

            tools.execute(TicketAgentTools.TOOL_QUERY_TICKETS, "{}");

            ArgumentCaptor<TicketQuery> captor = ArgumentCaptor.forClass(TicketQuery.class);
            verify(ticketService).page(captor.capture());
            assertThat(captor.getValue().getScope()).isEqualTo("mine");
        }

        @Test
        @DisplayName("模型传了乱造的 scope 时由 Service 报错，工具转为错误说明而不是抛出")
        void invalidScopeDoesNotThrow() {
            when(ticketService.page(any(TicketQuery.class)))
                    .thenThrow(new com.enterprise.workorder.common.BusinessException("无效的 scope"));

            String json = tools.execute(TicketAgentTools.TOOL_QUERY_TICKETS, """
                    {"scope":"everything"}
                    """);

            assertThat(json).contains("error");
        }
    }

    @Nested
    @DisplayName("工具说明")
    class Specs {

        @Test
        @DisplayName("四个工具的 schema 都是合法 JSON —— 拼错了会在调用时被服务端拒绝")
        void schemasAreValidJson() throws Exception {
            assertThat(tools.specs()).hasSize(4);
            for (LlmClient.ToolSpec spec : tools.specs()) {
                JsonNode node = objectMapper.readTree(spec.parametersJson());
                assertThat(node.path("type").asText()).isEqualTo("object");
                assertThat(spec.description()).isNotBlank();
            }
        }

        @Test
        @DisplayName("未知工具名不抛异常，回传错误说明")
        void unknownToolDoesNotThrow() {
            assertThat(tools.execute("noSuchTool", "{}")).contains("error");
            verify(ticketService, never()).page(any());
        }
    }

    @Nested
    @DisplayName("工单详情")
    class Detail {

        private TicketVO vo;

        @BeforeEach
        void visibleTicket() {
            vo = new TicketVO();
            vo.setId(689L);
            vo.setTicketNo("TK20260923-0001");
            vo.setTitle("三楼打印机故障");
            vo.setStatus("PENDING");
            vo.setStatusLabel("审批中");
            vo.setCurrentStep(1);
            vo.setTotalStep(2);
            vo.setCurrentApproverName("李经理");
        }

        private void stubPage(TicketVO... records) {
            Page<TicketVO> page = new Page<>(1, 20);
            page.setTotal(records.length);
            page.setRecords(List.of(records));
            when(ticketService.page(any(TicketQuery.class))).thenReturn(page);
        }

        @Test
        @DisplayName("按单号定位后一并返回审批轨迹与操作时间线")
        void returnsDetailWithTrail() {
            stubPage(vo);

            ApprovalRecord record = new ApprovalRecord();
            record.setStep(1);
            record.setApproverName("李经理");
            record.setAction("REJECT");
            record.setComment("补充设备编号");
            record.setCreatedAt(LocalDateTime.of(2026, 9, 23, 10, 0));
            when(ticketService.history(689L)).thenReturn(List.of(record));

            TicketOpLogVO log = new TicketOpLogVO();
            log.setActionLabel("提交审批");
            log.setOperatorName("张三");
            log.setCreatedAt(LocalDateTime.of(2026, 9, 23, 9, 0));
            when(ticketService.opLogs(689L)).thenReturn(List.of(log));

            String json = tools.execute(TicketAgentTools.TOOL_GET_TICKET_DETAIL, """
                    {"ticketNo":"TK20260923-0001"}
                    """);

            assertThat(json).contains("TK20260923-0001").contains("审批中");
            assertThat(json).contains("驳回");
            assertThat(json).contains("补充设备编号");
            assertThat(json).contains("提交审批");
            // 关键断言：轨迹与时间线都走 Service 的可见性校验，本工具不自己查 Mapper
            verify(ticketService).history(689L);
            verify(ticketService).opLogs(689L);
        }

        @Test
        @DisplayName("单号查不到时不编造，回一份错误说明，且不去取轨迹")
        void notFoundDoesNotFabricate() {
            stubPage();

            String json = tools.execute(TicketAgentTools.TOOL_GET_TICKET_DETAIL, """
                    {"ticketNo":"TK00000000-0000"}
                    """);

            assertThat(json).contains("error");
            verify(ticketService, never()).history(any());
            verify(ticketService, never()).opLogs(any());
        }

        @Test
        @DisplayName("既没给单号也没给关键词时，不查全部，直接提示补充")
        void missingSearchTerm() {
            String json = tools.execute(TicketAgentTools.TOOL_GET_TICKET_DETAIL, "{}");

            assertThat(json).contains("error");
            verify(ticketService, never()).page(any());
        }

        @Test
        @DisplayName("轨迹过多时只保留最近若干条，避免淹没重点")
        void trailIsTruncated() {
            stubPage(vo);
            java.util.List<ApprovalRecord> many = new java.util.ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                ApprovalRecord r = new ApprovalRecord();
                r.setStep(i);
                r.setApproverName("审批人" + i);
                r.setAction("APPROVE");
                r.setComment("第" + i + "条意见");
                r.setCreatedAt(LocalDateTime.of(2026, 9, 23, 10, i));
                many.add(r);
            }
            when(ticketService.history(689L)).thenReturn(many);
            when(ticketService.opLogs(689L)).thenReturn(List.of());

            String json = tools.execute(TicketAgentTools.TOOL_GET_TICKET_DETAIL, """
                    {"ticketNo":"TK20260923-0001"}
                    """);

            assertThat(json).doesNotContain("第1条意见").contains("第20条意见");
        }
    }
}
