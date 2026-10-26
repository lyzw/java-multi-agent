package cn.studykid.multiagent.application.cap;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Skill 两级描述发布闸门测试（契约 {@code $defs.skillDescription}）。
 *
 * <p>⚠️ 超限必须在<b>发布申请阶段</b>拒绝（config.io 校验），
 * 不得带病进入指纹计算或发布快照。
 */
class SkillDescriptionSpecContractTest {

    @Test
    @DisplayName("✅ 合法的两级描述被接受，字段两端去空白")
    void acceptsAndTrimsValidDescription() {
        var spec = new ConfigPublishPayload.SkillDescriptionSpec(
                "  只读查询学生画像  ",
                List.of(new ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec(
                        "  用户提供学生标识  ", "  调用工具，id 取自用户原文  ")));

        assertThat(spec.purpose()).isEqualTo("只读查询学生画像");
        assertThat(spec.usage()).hasSize(1);
        assertThat(spec.usage().get(0).when()).isEqualTo("用户提供学生标识");
        assertThat(spec.usage().get(0).how()).isEqualTo("调用工具，id 取自用户原文");
    }

    @Test
    @DisplayName("⛔ purpose 超过 200 字 → 拒绝")
    void overlongPurposeIsRejected() {
        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec(
                "x".repeat(201), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
    }

    @Test
    @DisplayName("⛔ usage 超过 20 条 → 拒绝")
    void tooManyUsageEntriesIsRejected() {
        List<ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec> over = java.util
                .stream.IntStream.range(0, 21)
                .mapToObj(i -> new ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec(
                        "条件 " + i, "操作 " + i))
                .toList();

        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec("用途", over))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage");
    }

    @Test
    @DisplayName("⛔ 单条 when 超 200 字 / how 超 1000 字 → 拒绝")
    void overlongUsageFieldsAreRejected() {
        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec(
                "w".repeat(201), "how"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("when");

        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec(
                "when", "h".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("how");
    }

    @Test
    @DisplayName("⛔ 空白 purpose / when / how → 拒绝（描述不得是空壳）")
    void blankFieldsAreRejected() {
        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec("   ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");

        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec(
                "  ", "how"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("when");

        assertThatThrownBy(() -> new ConfigPublishPayload.SkillDescriptionSpec.SkillUsageSpec(
                "when", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("how");
    }
}
