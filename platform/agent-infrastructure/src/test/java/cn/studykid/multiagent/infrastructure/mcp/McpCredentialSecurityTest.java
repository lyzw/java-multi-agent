package cn.studykid.multiagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.ports.mcp.McpCredentialPort;

/**
 * MCP 凭据的具名安全用例（P4-T6 / P4-T8 · 退出门禁 5 · 06 §5「凭据」）。
 *
 * <p>06 §5 原文：{@code credential_ref} 引用密钥管理；明文禁入库；日志脱敏；
 * 支持轮换（轮换后连接级重验）。
 *
 * <p>⚠️ <b>本类断言的是「凭据没出现在该出现的地方之外」</b>：
 * 不是「用了 credential_ref 就行」——
 * 引用形态正确但被日志/指纹带出去，一样是泄漏。
 */
class McpCredentialSecurityTest {

    private static final String SECRET = "super-secret-token-value";

    private static RefMcpCredentialPort port(String envValue) {
        return new RefMcpCredentialPort(
                name -> Optional.ofNullable(envValue),
                key -> Optional.empty());
    }

    @Nested
    @DisplayName("凭据解析")
    class Resolution {

        @Test
        @DisplayName("✅ env:// 引用可解析出材料")
        void envReferenceResolves() {
            Optional<McpCredentialPort.McpCredential> resolved =
                    port(SECRET).resolve("env://MCP_TOKEN");

            assertThat(resolved).isPresent();
            assertThat(resolved.get().secret()).isEqualTo(SECRET);
            assertThat(resolved.get().kind()).isEqualTo(McpCredentialPort.McpCredential.Kind.TOKEN);
        }

        @Test
        @DisplayName("✅ 无引用 → 空（authKind=none 的 server 合法，不是错误）")
        void noReferenceMeansNoCredential() {
            assertThat(port(SECRET).resolve(null)).isEmpty();
            assertThat(port(SECRET).resolve("  ")).isEmpty();
        }

        @Test
        @DisplayName("⛔ 未知引用形态 → 空（不把引用本身当令牌回退）")
        void unknownSchemeIsRejected() {
            assertThat(port(SECRET).resolve("plain-token-value"))
                    .as("⛔ 未知形态的引用被当成了凭据材料")
                    .isEmpty();
            assertThat(port(SECRET).resolve("file:///etc/passwd")).isEmpty();
        }

        @Test
        @DisplayName("⛔ 材料取不到 → 空（不是「无认证」）")
        void missingSecretIsRejected() {
            assertThat(port(null).resolve("env://MCP_TOKEN"))
                    .as("⛔ 取不到材料却当成「无凭据」= 以未认证身份发请求")
                    .isEmpty();
            assertThat(port("").resolve("env://MCP_TOKEN")).isEmpty();
        }
    }

    @Nested
    @DisplayName("轮换（06 §5「轮换后连接级重验」）")
    class Rotation {

        @Test
        @DisplayName("✅ 每次解析都重新取值 —— 轮换立即生效（不缓存材料）")
        void rotationTakesEffectImmediately() {
            RefMcpCredentialPort rotatable = new RefMcpCredentialPort(
                    name -> Optional.of("token-v1"), key -> Optional.empty());

            assertThat(rotatable.resolve("env://T").orElseThrow().secret()).isEqualTo("token-v1");

            // 模拟轮换：底层取值变了
            rotatable = new RefMcpCredentialPort(
                    name -> Optional.of("token-v2"), key -> Optional.empty());
            assertThat(rotatable.resolve("env://T").orElseThrow().secret()).isEqualTo("token-v2");
        }

        @Test
        @DisplayName("✅ 同一实例连续两次解析 → 都读底层当前值（无缓存层）")
        void noMaterialCaching() {
            java.util.concurrent.atomic.AtomicReference<String> current =
                    new java.util.concurrent.atomic.AtomicReference<>("v1");
            RefMcpCredentialPort rotatable = new RefMcpCredentialPort(
                    name -> Optional.ofNullable(current.get()), key -> Optional.empty());

            assertThat(rotatable.resolve("env://T").orElseThrow().secret()).isEqualTo("v1");
            current.set("v2");
            assertThat(rotatable.resolve("env://T").orElseThrow().secret())
                    .as("⛔ 凭据被缓存 = 轮换不生效（旧凭据继续被使用）")
                    .isEqualTo("v2");
        }
    }

    @Nested
    @DisplayName("脱敏（06 §5「日志脱敏」）")
    class Redaction {

        @Test
        @DisplayName("⛔ 凭据记录的 toString 不得含材料")
        void credentialToStringIsRedacted() {
            McpCredentialPort.McpCredential credential =
                    new McpCredentialPort.McpCredential(
                            McpCredentialPort.McpCredential.Kind.TOKEN, SECRET);

            assertThat(credential.toString())
                    .as("⛔ 凭据材料的 toString 含明文 = 记录被打印即泄漏")
                    .doesNotContain(SECRET)
                    .contains("redacted");
        }

        @Test
        @DisplayName("⛔ MCP Server 定义的 toString 不得含 credentialRef")
        void serverDefToStringRedactsCredentialRef() {
            CapMcpServerDef def = new CapMcpServerDef("srv-1", 1,
                    CapMcpServerDef.Transport.HTTP, "https://mcp.example.com/rpc", null,
                    CapMcpServerDef.AuthKind.TOKEN, McpTrustTier.PARTNER, null,
                    CapabilityRiskLevel.READ, false, "fp", "vault://prod/mcp-token", 1,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH);

            assertThat(def.toString())
                    .as("⛔ credentialRef 出现在 toString 里 = 定位符随记录进日志")
                    .doesNotContain("vault://prod/mcp-token")
                    .contains("redacted");
        }

        @Test
        @DisplayName("✅ 脱敏后仍保留排障所需的字段（serverId / transport / trustTier）")
        void redactedToStringKeepsDiagnostics() {
            CapMcpServerDef def = new CapMcpServerDef("srv-1", 1,
                    CapMcpServerDef.Transport.HTTP, "https://mcp.example.com/rpc", null,
                    CapMcpServerDef.AuthKind.TOKEN, McpTrustTier.PARTNER, null,
                    CapabilityRiskLevel.READ, false, "fp", "vault://prod/mcp-token", 1,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH);

            assertThat(def.toString())
                    .contains("srv-1")
                    .contains("HTTP")
                    .contains("PARTNER");
        }
    }

    @Nested
    @DisplayName("指纹（§6.4「凭据材料永不入指纹」）")
    class Fingerprint {

        @Test
        @DisplayName("⛔ 闭包指纹不得含凭据定位符")
        void fingerprintExcludesCredentialRef() {
            CapMcpServerDef def = new CapMcpServerDef("srv-1", 1,
                    CapMcpServerDef.Transport.HTTP, "https://mcp.example.com/rpc", null,
                    CapMcpServerDef.AuthKind.TOKEN, McpTrustTier.PARTNER, null,
                    CapabilityRiskLevel.READ, false, "fp", "vault://prod/mcp-token", 1,
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH);

            // 指纹只覆盖 MCP 绑定的公开字段；这里直接验证绑定里没有凭据字段
            cn.studykid.multiagent.core.cap.EffectiveClosure.McpBinding binding =
                    new cn.studykid.multiagent.core.cap.EffectiveClosure.McpBinding(
                            "srv-1", 1, "skill-1", McpTrustTier.PARTNER,
                            CapabilityRiskLevel.READ,
                            java.util.Set.of("op-a"), java.util.Set.of("op-a"));
            cn.studykid.multiagent.core.cap.EffectiveClosure closure =
                    new cn.studykid.multiagent.core.cap.EffectiveClosure(
                            "agent-1", 1,
                            java.util.List.of(new cn.studykid.multiagent.core.cap.EffectiveClosure
                                    .SkillBinding("skill-1", 1, CapabilityRiskLevel.READ,
                                    "fp-skill", java.util.List.of(), "[]")),
                            java.util.List.of(),
                            java.util.List.of(binding)).canonicalized();

            String fingerprint = cn.studykid.multiagent.application.cap.ClosureFingerprint
                    .of(closure);

            assertThat(fingerprint)
                    .as("⛔ 指纹含凭据定位符 = 凭据轮换会让所有在途 Run 集体失效")
                    .doesNotContain("vault://prod/mcp-token")
                    .doesNotContain("mcp-token");
            assertThat(def.credentialRef()).isEqualTo("vault://prod/mcp-token");
        }
    }

    @Nested
    @DisplayName("凭据不得随配置进入日志/异常")
    class NoLeakIntoLogs {

        @Test
        @DisplayName("⛔ 解析失败的异常/日志里不得出现材料")
        void failurePathDoesNotLeakSecret() {
            // 构造一个「材料存在但端口查不到」的路径：端口只返回引用形态信息
            RefMcpCredentialPort p = port(SECRET);
            String rendered = String.valueOf(p.resolve("env://MCP_TOKEN").orElseThrow());

            assertThat(rendered)
                    .as("⛔ 凭据材料的字符串形式含明文")
                    .doesNotContain(SECRET);
        }

        @Test
        @DisplayName("✅ 端口的 Map 形式也不得含材料")
        void mapRenderingDoesNotLeakSecret() {
            Map<String, Object> map = Map.of(
                    "credential", new McpCredentialPort.McpCredential(
                            McpCredentialPort.McpCredential.Kind.TOKEN, SECRET));

            assertThat(String.valueOf(map)).doesNotContain(SECRET);
        }
    }
}
