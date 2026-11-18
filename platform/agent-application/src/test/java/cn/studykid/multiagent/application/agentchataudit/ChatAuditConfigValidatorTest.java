package cn.studykid.multiagent.application.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;

/**
 * 对话审计配置校验单元测试。
 *
 * <p><b>⚠️ 为什么必须单元层覆盖</b>：校验是写入期 fail-closed 门禁——
 * 一旦失效就会出现「审计失败中断业务」（违反决策 6）或「路径穿越写任意目录」
 * （安全缺陷）。这类不变量必须有不依赖数据库的反证测试。
 */
@DisplayName("对话审计配置校验")
class ChatAuditConfigValidatorTest {

    @Test
    @DisplayName("✅ 默认配置合法（7 项决策的安全默认）")
    void defaultsAreValid() {
        ChatAuditConfig c = ChatAuditConfig.defaults();
        assertThatCode(() -> ChatAuditConfigValidator.validate(c)).doesNotThrowAnyException();
        assertThat(c.enabled()).isTrue();
        assertThat(c.sinks().database().enabled()).isTrue();
        assertThat(c.sinks().file().enabled()).isTrue();
        assertThat(c.capture().deltaChunks()).isTrue();
        assertThat(c.capture().thinking()).isTrue();
        assertThat(c.sampling().rate()).isEqualTo(1.0d);
        assertThat(c.writer().onSinkError()).isEqualTo(ChatAuditConfig.Writer.IGNORE);
    }

    @Test
    @DisplayName("✅ null 整表归一化到默认，不抛 NPE")
    void normalizeNullToDefaults() {
        ChatAuditConfig n = ChatAuditConfigValidator.normalize(null);
        assertThat(n).isEqualTo(ChatAuditConfig.defaults());
        assertThatCode(() -> ChatAuditConfigValidator.validate(n)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("❌ onSinkError=PROPAGATE 被拒（审计失败不得中断业务）")
    void rejectPropagate() {
        ChatAuditConfig c = new ChatAuditConfig(
                true, ChatAuditConfig.Scope.defaults(), ChatAuditConfig.Sinks.defaults(),
                ChatAuditConfig.Capture.defaults(), ChatAuditConfig.Redaction.defaults(),
                ChatAuditConfig.Sampling.defaults(),
                new ChatAuditConfig.Writer(true, 10000, "PROPAGATE", 20000),
                ChatAuditConfig.Retention.defaults(), ChatAuditConfig.Limits.defaults());
        assertThatThrownBy(() -> ChatAuditConfigValidator.validate(c))
                .isInstanceOf(ChatAuditConfigInvalidException.class)
                .hasMessageContaining("onSinkError");
    }

    @Test
    @DisplayName("❌ 采样率越界被拒")
    void rejectBadSampling() {
        ChatAuditConfig c = new ChatAuditConfig(
                true, ChatAuditConfig.Scope.defaults(), ChatAuditConfig.Sinks.defaults(),
                ChatAuditConfig.Capture.defaults(), ChatAuditConfig.Redaction.defaults(),
                new ChatAuditConfig.Sampling(1.5d), ChatAuditConfig.Writer.defaults(),
                ChatAuditConfig.Retention.defaults(), ChatAuditConfig.Limits.defaults());
        assertThatThrownBy(() -> ChatAuditConfigValidator.validate(c))
                .isInstanceOf(ChatAuditConfigInvalidException.class)
                .hasMessageContaining("sampling.rate");
    }

    @Test
    @DisplayName("❌ 保留期越界被拒（PIPL 留存上限）")
    void rejectBadRetention() {
        ChatAuditConfig c = new ChatAuditConfig(
                true, ChatAuditConfig.Scope.defaults(), ChatAuditConfig.Sinks.defaults(),
                ChatAuditConfig.Capture.defaults(), ChatAuditConfig.Redaction.defaults(),
                ChatAuditConfig.Sampling.defaults(), ChatAuditConfig.Writer.defaults(),
                new ChatAuditConfig.Retention(365), ChatAuditConfig.Limits.defaults());
        assertThatThrownBy(() -> ChatAuditConfigValidator.validate(c))
                .isInstanceOf(ChatAuditConfigInvalidException.class)
                .hasMessageContaining("retention.days");
    }

    @Test
    @DisplayName("❌ 非法端点被拒（白名单）")
    void rejectUnknownEndpoint() {
        ChatAuditConfig.Scope scope = new ChatAuditConfig.Scope(
                java.util.List.of("/api/secret/endpoint"), true, true);
        ChatAuditConfig c = new ChatAuditConfig(
                true, scope, ChatAuditConfig.Sinks.defaults(), ChatAuditConfig.Capture.defaults(),
                ChatAuditConfig.Redaction.defaults(), ChatAuditConfig.Sampling.defaults(),
                ChatAuditConfig.Writer.defaults(), ChatAuditConfig.Retention.defaults(),
                ChatAuditConfig.Limits.defaults());
        assertThatThrownBy(() -> ChatAuditConfigValidator.validate(c))
                .isInstanceOf(ChatAuditConfigInvalidException.class)
                .hasMessageContaining("scope.endpoints");
    }

    @Test
    @DisplayName("❌ 目录穿越 / 相对路径被拒")
    void rejectBadDirectory() {
        assertThat(ChatAuditConfigValidator.directoryProblem("/data/../etc")).contains("..");
        assertThat(ChatAuditConfigValidator.directoryProblem("relative/path")).contains("绝对");
        assertThat(ChatAuditConfigValidator.directoryProblem("")).contains("空");
        assertThat(ChatAuditConfigValidator.directoryProblem("/data/audit")).isNull();
    }

    @Test
    @DisplayName("❌ 非法分区被拒")
    void rejectBadPartition() {
        ChatAuditConfig.Sinks.FileSink file = new ChatAuditConfig.Sinks.FileSink(
                true, "/data/audit", "month", false);
        ChatAuditConfig c = new ChatAuditConfig(
                true, ChatAuditConfig.Scope.defaults(),
                new ChatAuditConfig.Sinks(ChatAuditConfig.Sinks.Database.defaults(), file),
                ChatAuditConfig.Capture.defaults(), ChatAuditConfig.Redaction.defaults(),
                ChatAuditConfig.Sampling.defaults(), ChatAuditConfig.Writer.defaults(),
                ChatAuditConfig.Retention.defaults(), ChatAuditConfig.Limits.defaults());
        assertThatThrownBy(() -> ChatAuditConfigValidator.validate(c))
                .isInstanceOf(ChatAuditConfigInvalidException.class)
                .hasMessageContaining("partition");
    }

    @Test
    @DisplayName("✅ 强制脱敏黑名单不可被 capture.headers 覆盖（安全不变量在此常量）")
    void forcedRedactionBlacklistPresent() {
        assertThat(ChatAuditConfig.FORCED_REDACTED_HEADERS)
                .contains("authorization", "cookie", "x-api-key");
    }
}
