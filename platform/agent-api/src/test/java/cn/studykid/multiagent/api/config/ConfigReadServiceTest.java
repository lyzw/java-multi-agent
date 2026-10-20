package cn.studykid.multiagent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionRegistry;
import cn.studykid.multiagent.core.tenant.TenantContext;

/**
 * C1 配置只读服务单元测试（P4-D07）。
 *
 * <p><b>验证目标</b>：
 * <ul>
 *   <li>声明值与 {@code effective} 标志正确（已接线的键 = true，仅声明未接线的 = false）；</li>
 *   <li>嵌套 map 键（{@code agent.tool.risk-level-map}）能被解析为声明值；</li>
 *   <li>零保留投影反映运行态：声明三家 + 均为 PENDING + failClosed=true + anyConfirmed=false；</li>
 *   <li><b>⚠️ 绝不暴露任何密钥 / 口令</b>：响应中不得出现 {@code private-key} /
 *       {@code RABBITMQ_PASSWORD} 等敏感键。</li>
 * </ul>
 *
 * <p>⚠️ 用 {@link MockEnvironment} 显式灌入与 yml 对应的属性，避免依赖真实 yml。
 */
@DisplayName("P4-D07 C1 配置只读服务")
class ConfigReadServiceTest {

    private StandardEnvironment env;
    private ConfigReadService service;

    @BeforeEach
    void setUp() {
        env = new StandardEnvironment();

        // ⚠️ 用 MapPropertySource 灌入与 yml 对应的属性（避免依赖真实 yml）。
        //    MapPropertySource 位于 spring-core 主包，无需 spring-test 的 MockEnvironment
        //    （Spring Framework 7 已移除该类）。
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        // ── effective = true（已接入运行时）──
        props.put("agent.backpressure.enabled", "true");
        props.put("agent.rate-limit.limit-per-window", "30");
        props.put("agent.worker.enabled", "true");
        props.put("deployment.pilot.max-tenants", "1");
        props.put("agent.budget.reserve-per-tool-call", "0.50");
        props.put("auth.jwt.token-ttl-seconds", "900");
        props.put("agent.retention.enabled", "true");
        props.put("agent.retention.profile-months", "12");

        // ── effective = false（仅声明未接线）──
        props.put("agent.run.max-cost-per-run", "2.00");
        props.put("agent.tenant.max-concurrent-runs", "5");
        props.put("agent.approval.required-by-risk.READ", "NOT_REQUIRED");
        props.put("agent.approval.required-by-risk.WRITE_PROFILE", "OPTIONAL");
        props.put("agent.approval.required-by-risk.WRITE_EXTERNAL", "REQUIRED");
        props.put("agent.approval.ttl", "15m");
        props.put("agent.approval.approver-scope", "PARENT_OR_TEACHER");

        // ── 嵌套 map 键 ──
        props.put("agent.tool.risk-level-map.get_student_profile", "READ");
        props.put("agent.tool.risk-level-map.record_weakness", "WRITE_PROFILE");

        // ── 零保留声明 ──
        props.put("agent.compliance.zero-retention.confirmed-providers",
                "deepseek,minimax,workbuddy");
        props.put("agent.compliance.zero-retention.validity-months", "12");
        props.put("agent.compliance.zero-retention.expiry-warn-days", "30");

        env.getPropertySources().addFirst(new MapPropertySource("config-test", props));

        ZeroRetentionGate gate = new ZeroRetentionGate(
                ZeroRetentionRegistry.builder()
                        .pending("deepseek")
                        .pending("minimax")
                        .pending("workbuddy")
                        .build(),
                Clock.systemUTC());
        service = new ConfigReadService(env, gate);
    }

    /** 在响应里按 key 找一条配置条目。 */
    private ConfigEntry entryByKey(ConfigResponse resp, String key) {
        return resp.groups().stream()
                .flatMap(g -> g.entries().stream())
                .filter(e -> e.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少配置条目: " + key));
    }

    @Nested
    @DisplayName("R-1 声明值与 effective 标志正确")
    class EffectiveFlags {

        @Test
        @DisplayName("R-1a 已接线键：effective=true 且声明值正确")
        void wiredKeyEffective() {
            ConfigEntry e = entryByKey(service.snapshot(), "agent.backpressure.enabled");
            assertThat(e.effective()).isTrue();
            assertThat(e.declaredValue()).isEqualTo("true");
        }

        @Test
        @DisplayName("R-1b 仅声明键：effective=false 且声明值正确")
        void declaredOnlyKeyNotEffective() {
            // ⚠️ P4-D11：原先这里断言的是 `agent.run.max-cost-per-run`（当时未接线）。
            //    该键已接线（P4-D11 D11-1），故改用**真正仍未接线**的键：
            //    `agent.tenant.max-concurrent-runs`（常量定义外引用数 0 → 并发无限制）。
            ConfigEntry e = entryByKey(service.snapshot(), "agent.tenant.max-concurrent-runs");
            assertThat(e.effective()).isFalse();
            assertThat(e.declaredValue()).isEqualTo("5");
        }

        @Test
        @DisplayName("R-1b2 P4-D11：已接线的运行上限键 effective=true")
        void wiredRunLimitsEffective() {
            // ⚠️ 接线前生产快照是**硬编码**的（成本上限 10.00），
            //    yml 的 2.00 无任何生产读取点 —— 声明比真实敞口紧 5 倍。
            //    P4-D11 起由 RunConfigSnapshotFactory 读 yml，故 effective=true。
            ConfigResponse resp = service.snapshot();
            assertThat(entryByKey(resp, "agent.run.max-cost-per-run").effective()).isTrue();
            assertThat(entryByKey(resp, "agent.run.max-steps").effective()).isTrue();
            assertThat(entryByKey(resp, "agent.run.max-llm-calls").effective()).isTrue();
            assertThat(entryByKey(resp, "agent.approval.required-by-risk").effective()).isTrue();
        }

        @Test
        @DisplayName("R-1b3 P4-D11：零保留供应商清单已接入注册表（C9），不再标为未接线")
        void zeroRetentionProvidersWired() {
            // ⚠️ 此前把「是否接入」与「证据是否确认」混为一谈：
            //    键确实已被 C9 读取（三家均 PENDING 是**证据状态**，由 P17 页单独呈现）。
            assertThat(entryByKey(service.snapshot(),
                    "agent.compliance.zero-retention.confirmed-providers").effective())
                    .isTrue();
        }

        @Test
        @DisplayName("R-1c 令牌 TTL 暴露为已接线（非敏感）")
        void tokenTtlExposed() {
            ConfigEntry e = entryByKey(service.snapshot(), "auth.jwt.token-ttl-seconds");
            assertThat(e.effective()).isTrue();
            assertThat(e.declaredValue()).isEqualTo("900");
        }

        @Test
        @DisplayName("R-1d effectiveFlags 投影与条目一致")
        void effectiveFlagsProjection() {
            ConfigResponse resp = service.snapshot();
            assertThat(resp.effectiveFlags().get("agent.backpressure.enabled")).isTrue();
            assertThat(resp.effectiveFlags().get("agent.run.max-cost-per-run")).isTrue();
            assertThat(resp.effectiveFlags().get("agent.tenant.max-concurrent-runs")).isFalse();
        }
    }

    @Nested
    @DisplayName("R-2 嵌套 map 键可被解析")
    class MapKeys {

        @Test
        @DisplayName("R-2a risk-level-map 解析为含 READ/WRITE_PROFILE 的声明值")
        void riskLevelMapResolved() {
            ConfigEntry e = entryByKey(service.snapshot(), "agent.tool.risk-level-map");
            assertThat(e.declaredValue())
                    .as("Binder 必须能把扁平子键聚合成 map 表示")
                    .contains("READ")
                    .contains("WRITE_PROFILE");
            // ⚠️ P4-D11：风险映射由「惰性镜像」升级为「**启动守卫**」——
            //    yml 与工具代码声明不一致即拒绝启动（fail-closed）。
            //    故它不再「未接入」。
            assertThat(e.effective()).isTrue();
        }
    }

    @Nested
    @DisplayName("R-3 零保留投影反映运行态")
    class ZeroRetentionProjection {

        @Test
        @DisplayName("R-3a 声明三家 + 均为 PENDING + failClosed + 无确认")
        void zeroRetentionState() {
            ZeroRetentionView zr = service.snapshot().zeroRetention();
            assertThat(zr.declaredProviders())
                    .containsExactlyInAnyOrder("deepseek", "minimax", "workbuddy");
            assertThat(zr.registryStatus().values())
                    .as("⚠️ 仅声明未确认 → 必须 PENDING，不得显示 CONFIRMED")
                    .allSatisfy(s -> assertThat(s).isEqualTo("PENDING"));
            assertThat(zr.anyConfirmed()).isFalse();
            assertThat(zr.failClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("R-4 诚实性：绝不暴露密钥 / 口令")
    class NoSecrets {

        @Test
        @DisplayName("R-4a 响应中不含任何敏感键")
        void noSecretKeys() {
            ConfigResponse resp = service.snapshot();
            List<String> allKeys = resp.groups().stream()
                    .flatMap(g -> g.entries().stream())
                    .map(ConfigEntry::key)
                    .toList();
            assertThat(allKeys)
                    .as("⚠️ 绝不得把私钥/口令键纳入响应")
                    .doesNotContain("auth.jwt.private-key")
                    .doesNotContain("RABBITMQ_PASSWORD")
                    .doesNotContain("RABBITMQ_USER");
        }

        @Test
        @DisplayName("R-4b 敏感键的声明值也绝不被读取")
        void privateKeyNeverRead() {
            // ⚠️ 即便 yml 里填了私钥，服务也从不读取它（不在目录中）
            env.getPropertySources().addFirst(new MapPropertySource("config-test-secret",
                    java.util.Map.of("auth.jwt.private-key", "SUPER_SECRET_KEY_MATERIAL")));
            ConfigResponse resp = service.snapshot();
            String joined = resp.groups().stream()
                    .flatMap(g -> g.entries().stream())
                    .map(ConfigEntry::declaredValue)
                    .reduce("", (a, b) -> a + b);
            assertThat(joined)
                    .as("⚠️ 私钥明文绝不得出现在任何声明值中")
                    .doesNotContain("SUPER_SECRET_KEY_MATERIAL");
        }
    }
}
