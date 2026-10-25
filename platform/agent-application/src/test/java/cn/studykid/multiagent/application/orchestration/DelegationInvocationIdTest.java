package cn.studykid.multiagent.application.orchestration;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派调用标识的确定性派生测试（多级 Agent v1.1 · MA-T10 · FR-022）。
 *
 * <p>⚠️ <b>幂等的前提是标识可重算</b>：同一处委派（同一 Run + 同一父调用 + 同一模型
 * tool call）在重复投递、Worker 重启、接管重放时必须算出<b>同一个</b> invocationId。
 * 一旦用了随机 UUID，「重复消息不重复创建逻辑调用」就退化成了
 * 「每次重放都新建一次调用」。
 */
class DelegationInvocationIdTest {

    @Test
    @DisplayName("同一输入 → 同一 invocationId（可重算）")
    void sameInputYieldsSameId() {
        String first = DelegationInvocationId.derive("run-1", null, "call-1");
        String second = DelegationInvocationId.derive("run-1", null, "call-1");

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("不同 Run / 父调用 / tool call → 不同 invocationId（不得撞键）")
    void differentInputsYieldDifferentIds() {
        String base = DelegationInvocationId.derive("run-1", null, "call-1");

        assertThat(DelegationInvocationId.derive("run-2", null, "call-1")).isNotEqualTo(base);
        assertThat(DelegationInvocationId.derive("run-1", "parent-1", "call-1")).isNotEqualTo(base);
        assertThat(DelegationInvocationId.derive("run-1", null, "call-2")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("空位占位符不得让不同输入撞到同一个键")
    void nullPlaceholdersDoNotCollide() {
        // ⚠️ 'run-1|-|' 与 'run-1|-|call-1' 是两次不同的委派
        assertThat(DelegationInvocationId.derive("run-1", null, null))
                .isNotEqualTo(DelegationInvocationId.derive("run-1", null, "call-1"));
    }

    @Test
    @DisplayName("派生结果是合法 UUID（落进 UUID 列的格式约束）")
    void derivedIdIsAValidUuid() {
        String id = DelegationInvocationId.derive("run-1", null, "call-1");

        assertThat(UUID.fromString(id)).as("必须是可被 UUID 列接受的形式").isNotNull();
        assertThat(id).hasSize(36);
    }
}
