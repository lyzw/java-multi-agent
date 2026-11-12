package cn.studykid.multiagent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.context.CheckpointDelta;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * {@link CheckpointDeltaFactory} 行为测试（S3-M8-b / ADR-013 D49）。
 *
 * <p>锁死三件事：① 有新增才产 delta，游标 / 轮次 / 摘要 / 策略指纹逐项对齐；
 * ② 抽取回落、状态超长一律返回 null（不发射），绝不发空壳 checkpoint；
 * ③ 上一轮状态脏了按「无状态」重建，既不阻断本轮，也不续写脏状态。
 */
class CheckpointDeltaFactoryTest {

    private static final String POLICY = "cp_m8b_1";
    private final VerbatimSpanExtractor extractor = new VerbatimSpanExtractor();

    @Test
    @DisplayName("✅ 有新增即产 delta：游标 / 轮次 / 摘要 / 策略指纹逐项对齐")
    void buildsDeltaWhenNewRoundsExist() {
        CheckpointDelta delta = CheckpointDeltaFactory.build(input(null, 2), extractor);

        assertThat(delta).isNotNull();
        assertThat(delta.scopeKey()).isEqualTo("chat:run-1");
        assertThat(delta.modelId()).isEqualTo("model-a");
        assertThat(delta.policyVersion()).isEqualTo(POLICY);
        // 空状态起算：merger 保留 min(oldState.fromSeq, firstSeq) = 0（RV-32 语义，不假装覆盖从 1 开始）
        assertThat(delta.fromSeq()).isZero();
        assertThat(delta.toSeq()).isEqualTo(2);
        assertThat(delta.upToTurn()).isEqualTo(2);
        assertThat(delta.tier()).isEqualTo(2);
        assertThat(delta.checkpointTtlSeconds()).isEqualTo(1800);
        assertThat(delta.digest()).startsWith("sha256:");
        assertThat(delta.stateJson()).contains("你必须保留单位。");
        assertThat(delta.sourceMessageIds()).isEmpty();
        // 状态是可再次解析的确定性 JSON，且摘要与内容自洽
        StructuredState parsed = StructuredStateCodec.fromJson(delta.stateJson());
        assertThat(StructuredStateCodec.digest(parsed)).isEqualTo(delta.digest());
        assertThat(parsed.policyVersion()).isEqualTo(POLICY);
    }

    @Test
    @DisplayName("✅ 无新增轮次（游标已覆盖全部历史）→ null：不发射、不推进游标")
    void returnsNullWhenNothingNew() {
        String carryOver = StructuredStateCodec.toJson(new StructuredState(
                "", List.of("你必须保留单位。"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), 1, 2, POLICY));

        assertThat(CheckpointDeltaFactory.build(input(carryOver, 2), extractor)).isNull();
    }

    @Test
    @DisplayName("✅ 抽不到结构化内容 → null：绝不发「有 checkpoint 但内容为空」")
    void returnsNullWhenExtractionEmpty() {
        CheckpointDeltaFactory.Input input = new CheckpointDeltaFactory.Input(
                "chat:run-1", "model-a",
                List.of(ModelMessage.user("你好"), ModelMessage.assistant("你好，有什么可以帮你")),
                1, POLICY, null, 1, false, 1800, 8192);

        assertThat(CheckpointDeltaFactory.build(input, extractor)).isNull();
    }

    @Test
    @DisplayName("✅ 状态超长 → null：发出去也会被六项校验丢弃，不浪费字节")
    void returnsNullWhenStateTooLarge() {
        CheckpointDeltaFactory.Input input = new CheckpointDeltaFactory.Input(
                "chat:run-1", "model-a", history(), 2, POLICY, null, 2, false, 1800,
                /* maxStateChars */ 16);

        assertThat(CheckpointDeltaFactory.build(input, extractor)).isNull();
    }

    @Test
    @DisplayName("✅ 上一轮状态脏了按无状态重建：不阻断本轮，也不续写脏状态")
    void malformedCarryOverIsTreatedAsAbsent() {
        CheckpointDelta delta = CheckpointDeltaFactory.build(
                input("{\"broken\":true}", 2), extractor);

        assertThat(delta).isNotNull();
        assertThat(delta.fromSeq()).isZero();
        assertThat(delta.toSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("✅ 非法输入 fail-closed：tier 0 / 空策略指纹 / 非正 TTL 直接拒绝")
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> new CheckpointDeltaFactory.Input(
                "chat:run-1", "model-a", history(), 2, POLICY, null, 0, false, 1800, 8192))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier");
        assertThatThrownBy(() -> new CheckpointDeltaFactory.Input(
                "chat:run-1", "model-a", history(), 2, "  ", null, 1, false, 1800, 8192))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyVersion");
        assertThatThrownBy(() -> new CheckpointDeltaFactory.Input(
                "chat:run-1", "model-a", history(), 2, POLICY, null, 1, false, 0, 8192))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpointTtlSeconds");
    }

    private static CheckpointDeltaFactory.Input input(String carryOver, int historyTurns) {
        return new CheckpointDeltaFactory.Input(
                "chat:run-1", "model-a", history(), historyTurns, POLICY, carryOver,
                2, false, 1800, 8192);
    }

    private static List<ModelMessage> history() {
        return List.of(
                ModelMessage.user("你必须保留单位。"),
                ModelMessage.assistant("好的，我会保留单位。"));
    }
}
