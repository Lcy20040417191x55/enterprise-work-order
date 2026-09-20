package com.enterprise.workorder.service;

import com.enterprise.workorder.common.BusinessException;
import com.enterprise.workorder.dto.TicketCreateRequest;
import com.enterprise.workorder.dto.TicketTypeSaveRequest;
import com.enterprise.workorder.entity.Ticket;
import com.enterprise.workorder.entity.TicketType;
import com.enterprise.workorder.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单类型配置测试。
 *
 * <p><b>这一组测试的价值在于"配置错误提前暴露"</b>。审批链写错了不会有任何即时反馈 ——
 * 直到某天有人提交该类工单才报"审批链配置有误"，那时管理员早已忘记自己改过什么。
 * 这些用例把"错误必须在保存这一刻就被拦住"固化下来。</p>
 *
 * <p>@Transactional 回滚，不污染开发数据。用例自己造出所需的前提数据，
 * 不依赖库里"恰好有什么" —— 见 {@code Delete#shouldRejectDeleteWhenReferenced} 的说明。</p>
 */
@SpringBootTest
@Transactional
@DisplayName("工单类型配置")
class TicketTypeServiceTest {

    private static final long UID_ZHANGSAN = 2L;

    @Autowired
    private TicketTypeService ticketTypeService;

    @Autowired
    private TicketService ticketService;

    @AfterEach
    void tearDown() {
        AuthTestSupport.logout();
    }

    // ==================================================================
    //  查询
    // ==================================================================

    @Nested
    @DisplayName("查询")
    class Query {

        @Test
        @DisplayName("listEnabled 只返回启用的类型，且按 sort 排序")
        void listEnabledShouldOnlyReturnEnabled() {
            createType("TEST_ON", "测试-启用", "DEPT_LEADER", 90, 1);
            createType("TEST_OFF", "测试-停用", "DEPT_LEADER", 91, 0);

            List<TicketType> enabled = ticketTypeService.listEnabled();

            assertThat(enabled).extracting(TicketType::getCode)
                    .contains("TEST_ON")
                    .doesNotContain("TEST_OFF");
            assertThat(enabled).extracting(TicketType::getSort).isSorted();
        }

        @Test
        @DisplayName("listAll 要能返回停用的类型，否则管理员无法重新启用")
        void listAllShouldIncludeDisabled() {
            createType("TEST_OFF2", "测试-停用", "DEPT_LEADER", 92, 0);

            assertThat(ticketTypeService.listAll()).extracting(TicketType::getCode)
                    .contains("TEST_OFF2");
        }
    }

    // ==================================================================
    //  新建
    // ==================================================================

    @Nested
    @DisplayName("新建")
    class Create {

        @Test
        @DisplayName("正常新建应成功，并归一化编码与审批链")
        void shouldCreate() {
            TicketType type = createType("test_lower", "测试-小写编码", " dept_leader , admin ", 50, null);

            assertThat(type.getId()).isNotNull();
            // 编码转大写：若原样存下 "test_lower"，后续按 code 精确查询必然漏
            assertThat(type.getCode()).isEqualTo("TEST_LOWER");
            // 审批链去空白 + 转大写
            assertThat(type.getApprovalFlow()).isEqualTo("DEPT_LEADER,ADMIN");
            // 不传 enabled 视为启用：这是最常见的用法，不该因此变成停用
            assertThat(type.getEnabled()).isEqualTo(1);
        }

        @Test
        @DisplayName("编码重复应报错，且提示里带上具体编码")
        void shouldRejectDuplicateCode() {
            createType("TEST_DUP", "测试-重复A", "DEPT_LEADER", 51, 1);

            assertThatThrownBy(() -> createType("TEST_DUP", "测试-重复B", "DEPT_LEADER", 52, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("TEST_DUP");
        }

        /**
         * 这条同时验证 DTO 与 Service 的分工没有打架：
         * DTO 的 @Pattern 只校验"形状"（字母开头、只含字母数字下划线），
         * 允许小写通过；Service 负责统一转大写后再做唯一性校验。
         * 若注解也强制大写，"test_case" 会在校验阶段就被 400 拒掉，
         * 这条断言就会以"抛的是校验异常、消息里没有 TEST_CASE"而失败。
         */
        @Test
        @DisplayName("编码大小写不同视为同一个，不能并存")
        void shouldTreatCodeCaseInsensitively() {
            createType("TEST_CASE", "测试-大小写A", "DEPT_LEADER", 53, 1);

            assertThatThrownBy(() -> createType("test_case", "测试-大小写B", "DEPT_LEADER", 54, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("TEST_CASE");
        }

        @Test
        @DisplayName("审批链角色写错必须在保存时就被拦住，并指出是第几级")
        void shouldRejectUnknownRoleWithLevelNumber() {
            assertThatThrownBy(() -> createType(
                    "TEST_BADROLE", "测试-错误角色", "DEPT_LEADER,BOSS", 55, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 2 级")
                    .hasMessageContaining("BOSS");
        }

        @Test
        @DisplayName("审批链为空应拒绝：至少要有一级，否则单据无人可审")
        void shouldRejectEmptyFlow() {
            assertThatThrownBy(() -> createType("TEST_EMPTY", "测试-空链", "  ", 56, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("审批链不能为空");
        }

        @Test
        @DisplayName("审批链里的空白项应被丢弃，不能产生一个空的审批级")
        void shouldDropBlankFlowSegments() {
            TicketType type = createType("TEST_BLANK", "测试-空段", "DEPT_LEADER,,ADMIN,", 57, 1);

            assertThat(type.getApprovalFlow()).isEqualTo("DEPT_LEADER,ADMIN");
        }
    }

    // ==================================================================
    //  修改
    // ==================================================================

    @Nested
    @DisplayName("修改")
    class Update {

        @Test
        @DisplayName("只改名称不改编码不应报重复")
        void shouldAllowUpdatingWithoutChangingCode() {
            TicketType created = createType("TEST_UPD", "测试-原名称", "DEPT_LEADER", 60, 1);

            TicketTypeSaveRequest request = request("TEST_UPD", "测试-新名称", "DEPT_LEADER,ADMIN", 60, 1);
            TicketType updated = ticketTypeService.update(created.getId(), request);

            assertThat(updated.getName()).isEqualTo("测试-新名称");
            assertThat(updated.getApprovalFlow()).isEqualTo("DEPT_LEADER,ADMIN");
        }

        @Test
        @DisplayName("改成别人已用的编码应拒绝")
        void shouldRejectTakenCode() {
            createType("TEST_A", "测试-A", "DEPT_LEADER", 61, 1);
            TicketType b = createType("TEST_B", "测试-B", "DEPT_LEADER", 62, 1);

            assertThatThrownBy(() -> ticketTypeService.update(
                    b.getId(), request("TEST_A", "测试-B改", "DEPT_LEADER", 62, 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("TEST_A");
        }

        @Test
        @DisplayName("类型不存在应返回 404 语义，而不是普通业务错误")
        void shouldReportNotFound() {
            assertThatThrownBy(() -> ticketTypeService.update(
                    999999L, request("TEST_X", "不存在", "DEPT_LEADER", 1, 1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(com.enterprise.workorder.common.ResultCode.NOT_FOUND));
        }

        @Test
        @DisplayName("停用类型不应影响已用它的在途工单的审批 —— 停用只挡新建")
        void shouldAllowDisabling() {
            TicketType created = createType("TEST_DIS", "测试-停用", "DEPT_LEADER", 63, 1);

            TicketType updated = ticketTypeService.update(
                    created.getId(), request("TEST_DIS", "测试-停用", "DEPT_LEADER", 63, 0));

            assertThat(updated.getEnabled()).isZero();
            assertThat(ticketTypeService.listEnabled()).extracting(TicketType::getCode)
                    .doesNotContain("TEST_DIS");
        }
    }

    // ==================================================================
    //  删除
    // ==================================================================

    @Nested
    @DisplayName("删除")
    class Delete {

        @Test
        @DisplayName("没被任何工单使用的类型可以删除")
        void shouldDeleteUnusedType() {
            TicketType created = createType("TEST_DEL", "测试-可删", "DEPT_LEADER", 70, 1);

            ticketTypeService.delete(created.getId());

            assertThat(ticketTypeService.listAll()).extracting(TicketType::getCode)
                    .doesNotContain("TEST_DEL");
        }

        /**
         * 工单表只存 type_id。若类型被删，历史工单"这是什么类型的单"就永久丢失，
         * 列表页类型名变空白，审批链也再也算不出来。所以正解是"停用"而非"删除"。
         *
         * <p><b>为什么引用关系必须由用例自己造</b>：这条原先直接删种子类型 1（LEAVE），
         * 断言"它被工单引用所以删不掉"。这等于把"此刻库里恰好有用到 LEAVE 的工单"
         * 当成了断言前提 —— 而 ticket 表是可以被清空的（重跑 sql/schema.sql 就会，
         * 脚本开头是 DROP TABLE IF EXISTS ticket）。表一空，类型 1 便无人引用，
         * 删除成功，用例就以"期望抛异常但没有"失败：<b>代码没错，是用例自己站不稳</b>。
         * 现在由用例自己造出这条引用关系，无论库里有没有历史数据，结论都一致。</p>
         */
        @Test
        @DisplayName("已被工单引用的类型不能删除，并提示改用停用")
        void shouldRejectDeleteWhenReferenced() {
            TicketType referenced = createType("TEST_REF", "测试-被引用", "DEPT_LEADER", 71, 1);
            createTicketUsing(referenced.getId());

            assertThatThrownBy(() -> ticketTypeService.delete(referenced.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能删除")
                    .hasMessageContaining("停用");
        }

        @Test
        @DisplayName("类型不存在应返回 404 语义")
        void shouldReportNotFound() {
            assertThatThrownBy(() -> ticketTypeService.delete(999999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode())
                            .isEqualTo(com.enterprise.workorder.common.ResultCode.NOT_FOUND));
        }
    }

    // ==================================================================
    //  测试辅助
    // ==================================================================

    private TicketType createType(String code, String name, String flow, int sort, Integer enabled) {
        return ticketTypeService.create(request(code, name, flow, sort, enabled));
    }

    /**
     * 造一张引用指定类型的工单，用来制造"该类型已被引用"这个前提。
     *
     * <p>走真实的 {@link TicketService#create} 而不是直接 INSERT：用例不必知道 ticket
     * 表有哪些非空列，列改名或加列时也不会悄悄失效。建出来的数据同样随事务回滚。</p>
     */
    private Ticket createTicketUsing(Long typeId) {
        AuthTestSupport.loginAs(UID_ZHANGSAN);
        TicketCreateRequest create = new TicketCreateRequest();
        create.setTitle("类型删除用例的引用工单");
        create.setContent("由 TicketTypeServiceTest 创建，事务结束后回滚");
        create.setTypeId(typeId);
        return ticketService.create(create);
    }

    private TicketTypeSaveRequest request(String code, String name, String flow,
                                          int sort, Integer enabled) {
        TicketTypeSaveRequest request = new TicketTypeSaveRequest();
        request.setCode(code);
        request.setName(name);
        request.setDescription("由 TicketTypeServiceTest 创建，事务结束后回滚");
        request.setApprovalFlow(flow);
        request.setSort(sort);
        request.setEnabled(enabled);
        return request;
    }
}
