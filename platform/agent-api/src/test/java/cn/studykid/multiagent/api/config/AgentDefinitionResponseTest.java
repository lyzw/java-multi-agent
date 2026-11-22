package cn.studykid.multiagent.api.config;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理读端点<b>回显完整性</b>守卫（2026-11-10）。
 *
 * <p>⚠️ 编辑既有 Agent 的路径是「读 definition → 回填草稿 → 保存」。
 * 若响应 DTO 不带 {@code instructions} / {@code modelRef} /
 * {@code generationParams}，前端只能拿到空值，保存时会把三字段<b>覆盖成空</b> ——
 * 不报错、只是配置悄悄消失。
 *
 * <p>⚠️ <b>反向验证</b>：把 {@link CapAgentDefinitionController.AgentDefinitionResponse}
 * 的三字段从 {@code of(...)} 里摘掉，本用例必红。
 */
class AgentDefinitionResponseTest {

    @Test
    @DisplayName("✅ 定义详情回显提示词 / 模型 / 生成参数（编辑既有 Agent 不会被清空）")
    void responseCarriesRuntimeConfiguration() {
        CapAgentDef def = new CapAgentDef("tenant-a", "agent-a", 3, "辅导 Agent", "desc",
                List.of("TEACHER"), List.of(), List.of(), List.of(),
                "{}", "{}",
                "{\"inlineInstructions\":\"你是数学辅导助手\"}",
                "Model-A", "{\"temperature\":0.3,\"maxOutputTokens\":2048}",
                "fp", 1, Instant.EPOCH, Instant.parse("2026-11-10T00:00:00Z"));

        var response = CapAgentDefinitionController.AgentDefinitionResponse.of(def);

        assertThat(response.instructions()).contains("你是数学辅导助手");
        assertThat(response.modelRef()).isEqualTo("Model-A");
        assertThat(response.generationParams()).contains("0.3");
        assertThat(response.published()).isTrue();
        assertThat(response.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("✅ 历史版本回显版本变更时间与发布时间（配置对比需要两侧时间线）")
    void responseCarriesVersionTimestamps() {
        Instant created = Instant.parse("2026-11-18T09:30:00Z");
        Instant published = Instant.parse("2026-11-19T10:00:00Z");
        CapAgentDef def = new CapAgentDef("tenant-a", "agent-a", 2, "Agent", "d",
                List.of(), List.of(), List.of(), List.of(),
                "{}", "{}", "{}", null, "{}",
                "fp2", 1, created, published);

        var response = CapAgentDefinitionController.AgentDefinitionResponse.of(def);

        assertThat(response.createdAt()).isEqualTo(created);
        assertThat(response.publishedAt()).isEqualTo(published);
    }

    @Test
    @DisplayName("✅ 草稿版本的发布时间为空（不得与版本变更时间混用）")
    void draftHasNoPublishedAt() {
        Instant created = Instant.parse("2026-11-20T08:00:00Z");
        CapAgentDef def = new CapAgentDef("tenant-a", "agent-a", 4, "Draft", null,
                List.of(), List.of(), List.of(), List.of(),
                "{}", "{}", "{}", null, "{}",
                "fp4", 1, created, null);

        var response = CapAgentDefinitionController.AgentDefinitionResponse.of(def);

        assertThat(response.createdAt()).isEqualTo(created);
        assertThat(response.publishedAt()).isNull();
        assertThat(response.published()).isFalse();
    }

    @Test
    @DisplayName("✅ 未绑定模型 / 未声明提示词的旧行如实回显（不得臆造默认值）")
    void unboundModelIsNotInvented() {
        CapAgentDef def = new CapAgentDef("tenant-a", "agent-a", 1, "旧 Agent", null,
                List.of(), List.of(), List.of(), List.of(),
                "{}", "{}", "{}", null, "{}",
                "fp", 1, Instant.EPOCH, null);

        var response = CapAgentDefinitionController.AgentDefinitionResponse.of(def);

        assertThat(response.modelRef()).isNull();
        assertThat(response.instructions()).isEqualTo("{}");
        assertThat(response.generationParams()).isEqualTo("{}");
        assertThat(response.published()).isFalse();
    }
}
