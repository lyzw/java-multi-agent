package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容指纹<b>字段完整性</b>守卫（2026-11-10 · 06 §5.3 / 审查 §7）。
 *
 * <p>⚠️ <b>为什么单独写这组用例</b>：2026-11-09 的实测结论是
 * 「改提示词 + 换模型，{@code CapObjectFingerprint.of(CapAgentDef)} 的指纹完全不变」，
 * 而 06 号文档却声称三字段已计入 —— 文档与代码各说一套。
 * 指纹不含行为性字段的后果不是报错，而是<b>静默失真</b>：闭包失效检测、
 * 审批复用判定、同版本幂等重放全都看不出「配置其实变了」。
 *
 * <p>⚠️ <b>反向验证</b>：撤掉
 * {@code CapObjectFingerprint.of(CapAgentDef)} 里三行 {@code root.put(...)}，
 * 本类前三个用例必须<b>恰好变红</b>；断言值全部是本类自己写死的字面量，
 * <b>不引用被测实现的任何常量</b>（否则编译期内联会让断言跟着漂）。
 */
class CapDefinitionFingerprintTest {

    private static final Instant CREATED = Instant.parse("2026-11-10T00:00:00Z");

    private static CapAgentDef agent(String instructions, String modelRef,
                                     String generationParams) {
        return new CapAgentDef("tenant-a", "agent-a", 1, "辅导 Agent", "desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", "{}", instructions, modelRef, generationParams,
                "pending", 1, CREATED, null);
    }

    private static CapToolDef tool(String description) {
        return new CapToolDef("tool-a", 1, "工具 A", CapToolKind.NATIVE,
                CapabilityRiskLevel.READ, null, null, null,
                "{}", "{}", description, "pending", 1, CREATED, null);
    }

    @Test
    @DisplayName("✅ 改提示词 → 指纹变化（提示词是行为性内容，不是元数据）")
    void changingInstructionsChangesFingerprint() {
        String fpA = CapObjectFingerprint.of(
                agent("{\"inlineInstructions\":\"你是数学辅导助手\"}", "Model-A", "{}"));
        String fpB = CapObjectFingerprint.of(
                agent("{\"inlineInstructions\":\"你是语文辅导助手\"}", "Model-A", "{}"));
        assertThat(fpA).isNotEqualTo(fpB);
    }

    @Test
    @DisplayName("✅ 换模型 → 指纹变化（换模型 = 换行为，在途 Run 必须失效）")
    void changingModelRefChangesFingerprint() {
        String fpA = CapObjectFingerprint.of(agent("{}", "Model-A", "{}"));
        String fpB = CapObjectFingerprint.of(agent("{}", "Model-B", "{}"));
        assertThat(fpA).isNotEqualTo(fpB);
    }

    @Test
    @DisplayName("✅ 改生成参数 → 指纹变化（temperature / maxOutputTokens 参与行为）")
    void changingGenerationParamsChangesFingerprint() {
        String fpA = CapObjectFingerprint.of(
                agent("{}", "Model-A", "{\"temperature\":0.3}"));
        String fpB = CapObjectFingerprint.of(
                agent("{}", "Model-A", "{\"temperature\":0.9}"));
        assertThat(fpA).isNotEqualTo(fpB);
    }

    @Test
    @DisplayName("✅ 同一内容换键序与空白 → 指纹不变（jsonb 往返不得被读成内容变化）")
    void jsonKeyOrderAndWhitespaceAreCanonicalized() {
        String fpA = CapObjectFingerprint.of(agent(
                "{\"inlineInstructions\":\"你是数学辅导助手\"}", "Model-A",
                "{\"temperature\":0.3,\"maxOutputTokens\":2048}"));
        String fpB = CapObjectFingerprint.of(agent(
                "{ \"inlineInstructions\" : \"你是数学辅导助手\" }", "Model-A",
                "{\"maxOutputTokens\":2048, \"temperature\":0.3}"));
        assertThat(fpA).isEqualTo(fpB);
    }

    @Test
    @DisplayName("✅ 三字段与时间戳无关：草稿 → 发布态指纹不变")
    void publishingDoesNotChangeFingerprint() {
        CapAgentDef draft = agent("{\"inlineInstructions\":\"提示词\"}", "Model-A", "{}");
        CapAgentDef published = new CapAgentDef(draft.agentId(),
                draft.version(), draft.name(), draft.description(), draft.roles(),
                draft.enabledSkills(), draft.skillBundles(), draft.capabilityTags(),
                draft.overrides(), draft.orchestration(),
                draft.instructions(), draft.modelRef(), draft.generationParams(),
                draft.configFingerprint(), draft.schemaVersion(), draft.createdAt(),
                Instant.parse("2026-11-11T00:00:00Z"));
        assertThat(CapObjectFingerprint.of(published))
                .isEqualTo(CapObjectFingerprint.of(draft));
    }

    @Test
    @DisplayName("✅ 工具描述变化 → 指纹变化（描述是模型可见的行为定义）")
    void changingToolDescriptionChangesFingerprint() {
        assertThat(CapObjectFingerprint.of(tool("查询学生档案")))
                .isNotEqualTo(CapObjectFingerprint.of(tool("查询课程表")));
    }
}
