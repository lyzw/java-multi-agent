package cn.studykid.multiagent.ports.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 消息通道模式解析（P4-D17）。
 *
 * <p><b>⚠️ 本类断言的是「失败必须发生在启动期」</b>：
 * 通道写错的后果是「消息静默不消费」——
 * API 正常返回、无报错、无告警、Run 永远不动。
 * 若解析退��默认值，这个失败会推迟到「Run 不执行」时才被发现。
 */
class MessagingModeTest {

    @Test
    @DisplayName("✅ 合法值解析为对应模式")
    void parsesValidValues() {
        assertThat(MessagingMode.parse("rabbitmq")).isEqualTo(MessagingMode.RABBITMQ);
        assertThat(MessagingMode.parse("database")).isEqualTo(MessagingMode.DATABASE);
    }

    @Test
    @DisplayName("✅ 容忍大小写与首尾空白（配置手写常见形态）")
    void toleratesCaseAndSpaces() {
        assertThat(MessagingMode.parse("  Database ")).isEqualTo(MessagingMode.DATABASE);
        assertThat(MessagingMode.parse("RABBITMQ")).isEqualTo(MessagingMode.RABBITMQ);
    }

    @Test
    @DisplayName("⛔ null / 空 / 空白 → 拒绝（不得回落默认值）")
    void rejectsBlank() {
        assertThatThrownBy(() -> MessagingMode.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须显式声明");
        assertThatThrownBy(() -> MessagingMode.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessagingMode.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("⛔ 拼错的模式 → 拒绝，且错误信息列出合法值")
    void rejectsTypo() {
        assertThatThrownBy(() -> MessagingMode.parse("rabit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rabbitmq")
                .hasMessageContaining("database");
    }

    @Test
    @DisplayName("⛔ 近似但不等价的模式（postgres / db / in-memory）→ 一律拒绝")
    void rejectsNearMisses() {
        for (String bad : new String[]{"postgres", "db", "in-memory", "amqp", ""}) {
            assertThatThrownBy(() -> MessagingMode.parse(bad))
                    .as("非合法通道必须被拒: " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("✅ 合法值集合可枚举（供错误提示与自检使用）")
    void exposesKeys() {
        assertThat(MessagingMode.keys()).containsExactly("rabbitmq", "database");
    }
}
