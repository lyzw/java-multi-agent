package cn.studykid.multiagent.ports.agentchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ContextPolicyVersionPort} 的纯契约测试（S3-M8-a）。
 *
 * <p>锁死三件事：输入 fail-closed、{@code null} 的语义是「丢弃客户端状态」、
 * 端口只有 {@code resolve(Input)} 一个抽象操作（不许塞第二套策略读取入口）。
 */
class ContextPolicyVersionPortTest {

    private static final ContextPolicyVersionPort.Input VALID =
            new ContextPolicyVersionPort.Input(
                    "11111111-1111-1111-1111-111111111111", "agent-1", 3, "model-x", 2048);

    @Test
    @DisplayName("✅ 合法事实逐字保留，不隐式补默认模型 / 版本 / 输出上限")
    void validFactsAreKeptVerbatim() {
        assertThat(VALID.tenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(VALID.agentId()).isEqualTo("agent-1");
        assertThat(VALID.agentVersion()).isEqualTo(3);
        assertThat(VALID.modelId()).isEqualTo("model-x");
        assertThat(VALID.maxOutputTokens()).isEqualTo(2048);
    }

    @Test
    @DisplayName("⛔ 身份 / 固定版本 / 输出上限缺一即 fail-closed")
    void inputFailsClosed() {
        assertThatThrownBy(() -> new ContextPolicyVersionPort.Input(
                " ", "agent-1", 3, "model-x", 2048))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new ContextPolicyVersionPort.Input(
                "t", " ", 3, "model-x", 2048))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentId");
        assertThatThrownBy(() -> new ContextPolicyVersionPort.Input(
                "t", "agent-1", 3, " ", 2048))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelId");
        assertThatThrownBy(() -> new ContextPolicyVersionPort.Input(
                "t", "agent-1", 0, "model-x", 2048))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentVersion");
        assertThatThrownBy(() -> new ContextPolicyVersionPort.Input(
                "t", "agent-1", 3, "model-x", 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxOutputTokens");
    }

    @Test
    @DisplayName("⛔ null 只表示「无法裁决」：调用方必须丢弃 state，不得从信封反推当前策略")
    void nullMeansFailClosedNotFallback() {
        ContextPolicyVersionPort port = input -> null;
        assertThat(port.resolve(VALID)).isNull();
    }

    @Test
    @DisplayName("✅ 端口只有一个抽象操作 resolve(Input)，杜绝第二套策略读取入口")
    void portHasSingleAbstractOperation() {
        Method[] methods = ContextPolicyVersionPort.class.getDeclaredMethods();
        assertThat(java.util.Arrays.stream(methods)
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .map(Method::getName))
                .containsExactly("resolve");
        assertThat(ContextPolicyVersionPort.class.getRecordComponents())
                .as("接口不是 record：getRecordComponents() 必须返回 null")
                .isNull();
    }
}
