package com.enterprise.workorder.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机规则表测试。
 *
 * <p><b>为什么这个纯单元测试值得存在</b>：TicketStatus 里的 6 个 is* 方法是整个系统
 * "能不能做某事"的唯一出处 —— Service、Controller 的授权、前端按钮显隐都由它推出来。
 * 一旦某个状态被改错（例如把 WITHDRAWN 从"可改"里漏掉），表现是"某个按钮莫名点不了"，
 * 排查要一路从页面查到数据库。把规则做成一张可读的表，改错时测试会直接指出是哪个格子。</p>
 *
 * <p>不启动 Spring，不需要数据库 —— 规则不依赖任何外部状态。</p>
 */
@DisplayName("状态机规则表")
class TicketStatusTest {

    /**
     * 期望的行为矩阵：状态 -> {可改, 可删, 可作废, 可提交, 可审批, 可撤回}。
     *
     * <p>写成明文表格而不是散落在各测试方法里，是为了让"这个状态的规则到底是什么"
     * 一眼可见。新增状态时只需在这里加一行，下面的断言会自动覆盖它。</p>
     */
    private static final List<Object[]> MATRIX = List.of(
            //                        可改   可删   可作废 可提交 可审批 可撤回
            new Object[]{TicketStatus.DRAFT,     true,  true,  true,  true,  false, false},
            new Object[]{TicketStatus.PENDING,   false, false, false, false, true,  true},
            new Object[]{TicketStatus.APPROVED,  false, false, false, false, false, false},
            new Object[]{TicketStatus.REJECTED,  true,  false, true,  true,  false, false},
            new Object[]{TicketStatus.WITHDRAWN, true,  false, true,  true,  false, false},
            new Object[]{TicketStatus.CLOSED,    false, false, false, false, false, false}
    );

    @Nested
    @DisplayName("规则表逐格校验")
    class Matrix {

        @Test
        @DisplayName("每个状态的六项规则都必须与表格一致")
        void shouldMatchMatrix() {
            for (Object[] row : MATRIX) {
                TicketStatus status = (TicketStatus) row[0];
                assertThat(status.isEditable()).as("%s.isEditable", status).isEqualTo(row[1]);
                assertThat(status.isDeletable()).as("%s.isDeletable", status).isEqualTo(row[2]);
                assertThat(status.isCancelable()).as("%s.isCancelable", status).isEqualTo(row[3]);
                assertThat(status.isSubmittable()).as("%s.isSubmittable", status).isEqualTo(row[4]);
                assertThat(status.isApprovable()).as("%s.isApprovable", status).isEqualTo(row[5]);
                assertThat(status.isWithdrawable()).as("%s.isWithdrawable", status).isEqualTo(row[6]);
            }
        }

        @Test
        @DisplayName("表格必须覆盖全部状态，新增状态时不能忘记补规则")
        void matrixShouldCoverEveryStatus() {
            List<TicketStatus> covered = MATRIX.stream().map(r -> (TicketStatus) r[0]).toList();
            assertThat(covered).containsExactlyInAnyOrder(TicketStatus.values());
        }
    }

    @Nested
    @DisplayName("跨状态的规则不变式")
    class Invariants {

        @Test
        @DisplayName("只有从未提交过的草稿可删；任何已进过流程的状态都不可删")
        void onlyDraftIsDeletable() {
            assertThat(Arrays.stream(TicketStatus.values()).filter(TicketStatus::isDeletable))
                    .containsExactly(TicketStatus.DRAFT);
        }

        @Test
        @DisplayName("可删必然可改：不允许出现能删却不能改的状态")
        void deletableImpliesEditable() {
            for (TicketStatus status : TicketStatus.values()) {
                if (status.isDeletable()) {
                    assertThat(status.isEditable())
                            .as("%s 可删就必须可改，否则会留下无法修正的草稿", status).isTrue();
                }
            }
        }

        @Test
        @DisplayName("撤回与作废互斥：审批中的单只能撤回，不能直接作废")
        void withdrawableAndCancelableAreExclusive() {
            for (TicketStatus status : TicketStatus.values()) {
                assertThat(status.isWithdrawable() && status.isCancelable())
                        .as("%s 同时可撤回又可作废，会引出两个入口的优先级歧义", status).isFalse();
            }
        }

        @Test
        @DisplayName("只有审批中的单可审、可撤回")
        void onlyPendingIsActionableByApprover() {
            for (TicketStatus status : TicketStatus.values()) {
                boolean pending = status == TicketStatus.PENDING;
                assertThat(status.isApprovable()).isEqualTo(pending);
                assertThat(status.isWithdrawable()).isEqualTo(pending);
            }
        }

        @Test
        @DisplayName("每个状态都必须有非空中文标签，列表页靠它显示")
        void everyStatusHasLabel() {
            for (TicketStatus status : TicketStatus.values()) {
                assertThat(status.getLabel()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("已撤回的状态不是死胡同")
    class WithdrawnIsNotDeadEnd {

        /**
         * 这一组断言来自一次真实缺陷：早期版本把 WITHDRAWN 当作终态处理，
         * 结果是申请人撤回后既改不了也交不了，单据永远卡在那里，
         * 只能另建一张新单，历史记录白白断掉。
         */
        @Test
        @DisplayName("已撤回的单可以修改内容")
        void withdrawnIsEditable() {
            assertThat(TicketStatus.WITHDRAWN.isEditable()).isTrue();
        }

        @Test
        @DisplayName("已撤回的单可以重新提交")
        void withdrawnIsSubmittable() {
            assertThat(TicketStatus.WITHDRAWN.isSubmittable()).isTrue();
        }

        @Test
        @DisplayName("已撤回的单可以作废收尾")
        void withdrawnIsCancelable() {
            assertThat(TicketStatus.WITHDRAWN.isCancelable()).isTrue();
        }

        @Test
        @DisplayName("已撤回的单不可删除：它已经进过审批流，必须留痕")
        void withdrawnIsNotDeletable() {
            assertThat(TicketStatus.WITHDRAWN.isDeletable()).isFalse();
        }
    }
}
