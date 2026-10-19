package cn.studykid.multiagent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.studykid.multiagent.core.run.IllegalStateTransitionException;
import cn.studykid.multiagent.core.run.RunState;
import cn.studykid.multiagent.core.run.ToolOperationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Run 与 ToolOperation 状态机测试（FR-004 / AT-STATE-001/002）。
 *
 * <p>重点验证两条不可妥协的安全约束：
 * <ol>
 *   <li>终态不可迁出（防并发覆盖终态）；</li>
 *   <li>UNKNOWN <b>不能</b>直接重试，只能通过对账或人工处置离开。</li>
 * </ol>
 */
class StateMachineTest {

    @Nested
    @DisplayName("Run 状态机")
    class RunStates {

        @Test
        @DisplayName("QUEUED 可迁移至 RUNNING / CANCELLED")
        void queuedTransitions() {
            assertThat(RunState.QUEUED.canTransitionTo(RunState.RUNNING)).isTrue();
            assertThat(RunState.QUEUED.canTransitionTo(RunState.CANCELLED)).isTrue();
            assertThat(RunState.QUEUED.canTransitionTo(RunState.SUCCEEDED)).isFalse();
        }

        @Test
        @DisplayName("RUNNING 可迁移至四个终态")
        void runningTransitions() {
            assertThat(RunState.RUNNING.canTransitionTo(RunState.SUCCEEDED)).isTrue();
            assertThat(RunState.RUNNING.canTransitionTo(RunState.FAILED)).isTrue();
            assertThat(RunState.RUNNING.canTransitionTo(RunState.CANCELLED)).isTrue();
            assertThat(RunState.RUNNING.canTransitionTo(RunState.TIMED_OUT)).isTrue();
            assertThat(RunState.RUNNING.canTransitionTo(RunState.QUEUED)).isFalse();
        }

        @Test
        @DisplayName("全部终态不可迁出 —— 防并发覆盖终态")
        void terminalStatesAreFinal() {
            for (RunState terminal : new RunState[]{
                    RunState.CANCELLED, RunState.SUCCEEDED, RunState.FAILED, RunState.TIMED_OUT}) {
                assertThat(terminal.isTerminal()).as("%s 应为终态", terminal).isTrue();
                assertThat(terminal.legalTargets()).as("%s 不应有后继状态", terminal).isEmpty();
                for (RunState any : RunState.values()) {
                    assertThat(terminal.canTransitionTo(any))
                            .as("%s -> %s 应被拒绝", terminal, any)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("非法迁移抛异常而非静默忽略")
        void illegalTransitionThrows() {
            assertThatThrownBy(() -> RunState.SUCCEEDED.requireTransitionTo(RunState.RUNNING))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("非法状态迁移");
        }
    }

    @Nested
    @DisplayName("ToolOperation 状态机 —— UNKNOWN 语义")
    class ToolOperationStates {

        @Test
        @DisplayName("IN_FLIGHT 结果未知时进入 UNKNOWN")
        void inFlightToUnknown() {
            assertThat(ToolOperationState.IN_FLIGHT.canTransitionTo(ToolOperationState.UNKNOWN)).isTrue();
        }

        @Test
        @DisplayName("⚠️ UNKNOWN 不得直接重试 —— 只能通过对账或人工处置离开")
        void unknownCannotRetryDirectly() {
            assertThat(ToolOperationState.UNKNOWN.legalTargets())
                    .as("UNKNOWN 的唯一出路是对账或人工处置")
                    .containsExactlyInAnyOrder(
                            ToolOperationState.RECONCILED_SUCCEEDED,
                            ToolOperationState.RECONCILED_ABSENT,
                            ToolOperationState.MANUAL_DISPOSED);

            assertThat(ToolOperationState.UNKNOWN.canTransitionTo(ToolOperationState.PENDING))
                    .as("UNKNOWN -> PENDING 等同盲目重试，必须被拒绝")
                    .isFalse();
            assertThat(ToolOperationState.UNKNOWN.canTransitionTo(ToolOperationState.IN_FLIGHT))
                    .as("UNKNOWN -> IN_FLIGHT 等同盲目重试，必须被拒绝")
                    .isFalse();
        }

        @Test
        @DisplayName("UNKNOWN 被标记为需对账")
        void unknownRequiresReconciliation() {
            assertThat(ToolOperationState.UNKNOWN.requiresReconciliation()).isTrue();
            assertThat(ToolOperationState.SUCCEEDED.requiresReconciliation()).isFalse();
            assertThat(ToolOperationState.FAILED.requiresReconciliation()).isFalse();
        }

        @Test
        @DisplayName("明确失败可重试回 PENDING —— 与 UNKNOWN 语义相反")
        void definiteFailureCanRetry() {
            assertThat(ToolOperationState.FAILED.canTransitionTo(ToolOperationState.PENDING)).isTrue();
            assertThat(ToolOperationState.FAILED.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("对账收敛后进入终态")
        void reconciliationConverges() {
            assertThat(ToolOperationState.UNKNOWN.canTransitionTo(ToolOperationState.RECONCILED_SUCCEEDED)).isTrue();
            assertThat(ToolOperationState.UNKNOWN.canTransitionTo(ToolOperationState.RECONCILED_ABSENT)).isTrue();
            assertThat(ToolOperationState.RECONCILED_SUCCEEDED.isTerminal()).isTrue();
            assertThat(ToolOperationState.RECONCILED_ABSENT.isTerminal()).isTrue();
            assertThat(ToolOperationState.MANUAL_DISPOSED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("UNKNOWN 直接重试会抛异常")
        void unknownRetryThrows() {
            assertThatThrownBy(() -> ToolOperationState.UNKNOWN.requireTransitionTo(ToolOperationState.PENDING))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }
}
