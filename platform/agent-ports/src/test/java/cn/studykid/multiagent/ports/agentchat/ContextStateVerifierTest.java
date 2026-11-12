package cn.studykid.multiagent.ports.agentchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Absent;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Accepted;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Discarded;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.DropReason;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Input;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier.Result;

/**
 * S3-M8-a 请求侧 {@code contextState} 中性校验端口契约（ADR-013 D49）。
 *
 * <p>⚠️ 端口层只钉死「无 state / 接受 / 静默丢弃」三态与八项精确丢弃原因，
 * ⛔ 不引入算法层实现，避免 M6 冻结的 {@code ports/context} 四端口被污染。
 * 后续调用方必须把 {@code Absent}/{@code Discarded} 一律降级为全量历史；
 * {@code expectedPolicyVersion} 由已裁决 {@code EffectiveContextPolicy} 的持有方传入。
 */
class ContextStateVerifierTest {

    @Test
    @DisplayName("✅ Input 把 null/空白 clientState 归一为无 state；非空白逐字保留")
    void inputNormalizesBlankStateWithoutTrimmingRealState() {
        assertThat(new Input(null, "tenant-1", 3, "cp_v1").clientState()).isNull();
        assertThat(new Input("", "tenant-1", 3, "cp_v1").clientState()).isNull();
        assertThat(new Input("   ", "tenant-1", 3, "cp_v1").clientState()).isNull();
        assertThat(new Input("  env  ", "tenant-1", 3, "cp_v1").clientState()).isEqualTo("  env  ");
        assertThat(new Input("env", "tenant-1", 3, "  cp_v1  ")
                .expectedPolicyVersion()).isEqualTo("  cp_v1  ");
        assertThat(new Input("env", "tenant-1", 3, "  ")
                .expectedPolicyVersion()).isNull();
    }

    @Test
    @DisplayName("✅ Input 身份与轮次 fail-closed：空租户/负历史轮数直接抛，不进入降级路径")
    void inputRejectsUnsafeTenantOrCursor() {
        assertThatThrownBy(() -> new Input("state", " ", 0, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId")
                .hasMessageContaining("fail-closed");
        assertThatThrownBy(() -> new Input("state", null, 0, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new Input("state", "tenant-1", -1, "cp_v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("historyTurns");
    }

    @Test
    @DisplayName("✅ Result 三态封闭，八项 DropReason 精确定位六项不可信校验")
    void resultIsSealedAndDropReasonsAreFrozen() {
        assertThat(Result.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(Accepted.class, Absent.class, Discarded.class);
        assertThat(Accepted.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("stateJson", "policyVersion", "upToTurn", "digest");
        assertThat(Discarded.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("reason");

        assertThat(DropReason.values()).containsExactly(
                DropReason.TOO_LARGE,
                DropReason.MALFORMED,
                DropReason.HMAC_MISMATCH,
                DropReason.KEY_UNAVAILABLE,
                DropReason.EXPIRED,
                DropReason.POLICY_VERSION_MISMATCH,
                DropReason.TENANT_MISMATCH,
                DropReason.TURN_CURSOR_AHEAD);
    }

    @Test
    @DisplayName("✅ 三态实例均可构造，Discarded 理由不得为空（静默降级仍需可审计原因）")
    void resultRecordsKeepReasonsAndAcceptedPayload() {
        Accepted accepted = new Accepted("{\"k\":\"v\"}", "cp_v1", 3, "sha256:abc");
        assertThat(accepted.stateJson()).isEqualTo("{\"k\":\"v\"}");
        assertThat(accepted.policyVersion()).isEqualTo("cp_v1");
        assertThat(accepted.upToTurn()).isEqualTo(3);
        assertThat(accepted.digest()).isEqualTo("sha256:abc");

        assertThat(new Absent()).isInstanceOf(Result.class);
        assertThat(new Discarded(DropReason.EXPIRED).reason()).isEqualTo(DropReason.EXPIRED);
        assertThatThrownBy(() -> new Discarded(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Discarded.reason");
        assertThatThrownBy(() -> new Accepted(null, "cp_v1", 1, "sha256:abc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Accepted.stateJson");
        assertThatThrownBy(() -> new Accepted("{}", "cp_v1", 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Accepted.digest");
    }
}
