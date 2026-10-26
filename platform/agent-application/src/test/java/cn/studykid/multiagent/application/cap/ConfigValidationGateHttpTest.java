package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ReasonCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置期门禁对**远程 HTTP 工具**的校验（第三十七批 · C37-1 · §4.2 / §13.1）。
 *
 * <p>⚠️ <b>本类只覆盖「门禁独拦」的那几项</b>：
 * 方法白名单与超时上下界由更早的层拦下
 * （{@code ConfigPublishPayload.ToolSpec} 与 {@code CapHttpToolConfig} 的构造器），
 * 门禁里那两道是<b>纵深防御</b>，不是唯一防线；
 * 而<b>目标 URL 的形态</b>（协议 / 主机名 / 内嵌凭据 / 控制字符 / 端口）
 * 只有门禁查 —— 放在这里验，才是真的在验它。
 *
 * <p>⚠️ <b>门禁只判「形态」，不判「目标安不安全」</b>：
 * 「这个 URL 会不会解析到内网 / 云元数据地址」必须等 DNS 解析之后才能判定，
 * 那是<b>运行期</b>的出站防护（设计文档 §13.1）。
 * 配置期硬判 IP 有两个害处：写死 IP 段会误伤合法的内网部署；
 * 而在配置期做一次 DNS 解析，又必然与运行期的解析结果不一致（TOCTOU）。
 */
class ConfigValidationGateHttpTest {

    private final ConfigValidationGate gate = new ConfigValidationGate();

    private static CapToolDef httpTool(String id, String url) {
        CapHttpToolConfig http = new CapHttpToolConfig("GET", url, 10_000);
        return new CapToolDef(id, 1, id, CapToolKind.HTTP, CapabilityRiskLevel.READ,
                null, null, http, "{}", "{}", "fp-" + id, 1, Instant.EPOCH, null);
    }

    private static ConfigPublishCommand command(CapToolDef... tools) {
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, null,
                "requester-1", "approver-1", null,
                List.of(tools), List.of(), List.of(), List.of(), null);
    }

    @Test
    @DisplayName("✅ 合法的 HTTP 工具配置可以发布")
    void validHttpToolPassesGate() {
        assertThatCode(() -> gate.validate(command(
                httpTool("tool-1", "https://api.example.com/orders"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⛔ URL 不是 http/https → 配置期拒（file:// / gopher:// 是 SSRF 载体）")
    void nonHttpSchemeIsRejected() {
        assertThatThrownBy(() -> gate.validate(command(
                httpTool("tool-1", "file:///etc/passwd"))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("只允许 http / https")
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.HTTP_ENDPOINT_INVALID));
    }

    @Test
    @DisplayName("⛔ URL 内嵌凭据 → 配置期拒（凭据明文禁入库 · §4.2）")
    void urlWithUserInfoIsRejected() {
        assertThatThrownBy(() -> gate.validate(command(
                httpTool("tool-1", "https://user:secret@api.example.com/x"))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("不得内嵌凭据");
    }

    @Test
    @DisplayName("⛔ URL 缺主机名 / 含控制字符 → 配置期拒")
    void malformedUrlIsRejected() {
        assertThatThrownBy(() -> gate.validate(command(
                httpTool("tool-1", "https:///path-only"))))
                .isInstanceOf(ConfigPublishRejectedException.class);
        assertThatThrownBy(() -> gate.validate(command(
                httpTool("tool-1", "https://api.example.com/a\nb"))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("控制字符");
    }

    @Test
    @DisplayName("⛔ 端口越界 → 配置期拒")
    void portOutOfRangeIsRejected() {
        assertThatThrownBy(() -> gate.validate(command(
                httpTool("tool-1", "https://api.example.com:99999/x"))))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("端口越界");
    }

    @Test
    @DisplayName("⛔ HTTP 工具却携带 MCP 绑定 → 拒绝（目标必须唯一）")
    void httpToolWithMcpBindingIsRejected() {
        CapHttpToolConfig http = new CapHttpToolConfig("GET", "https://api.example.com/x", 10_000);
        assertThatThrownBy(() -> new CapToolDef("tool-1", 1, "t", CapToolKind.HTTP,
                CapabilityRiskLevel.READ, "srv-1", "op", http,
                "{}", "{}", "fp", 1, Instant.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得同时携带 MCP 绑定");
    }
}
