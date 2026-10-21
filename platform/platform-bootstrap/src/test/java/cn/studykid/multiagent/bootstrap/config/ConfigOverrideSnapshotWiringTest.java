package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.EffectiveConfigResolver;
import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.OverrideEntry;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.Tightening;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * 配置覆盖 → Run 快照的**连通性测试**（P4-D15 · D15-9）。
 *
 * <p>⚠️ <b>本类存在的唯一理由：它是 S-4 反证能转红的承重用例</b>。
 *
 * <p>首轮实现时，端点 IT 只验证了「{@code GET /api/config/overrides} 显示新值」，
 * 却<b>没有</b>验证「Run 快照真的用了覆盖值」——
 * 于是反证 S-4（把 {@code if (effectiveResolver != null && tenantId != null)}
 * 改成 {@code if (false)}）注入后<b>全套测试仍绿</b>。
 *
 * <p>这正是 P4-D15 §7 反复警告的形态：
 * <b>「看起来配好了、实际没用」</b>——
 * 读接口会如实显示新值，运维看不出任何异常，
 * 但每一笔 Run 用的都还是旧额度。
 * <b>读模型测试无法证伪这条，只有行为测试能。</b>
 *
 * <p>⚠️ 本类<b>不连数据库</b>：用内存替身回答覆盖查询，
 * 因为要验证的是「快照路径有没有读覆盖」，不是落库。
 * 落库由 {@code ConfigOverrideRepositoryIT} 覆盖。
 */
@DisplayName("P4-D15 配置覆盖接入 Run 快照（D15-9）")
class ConfigOverrideSnapshotWiringTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";

    /** 只对指定租户返回一个 max-steps=7 的覆盖。 */
    private static ConfigOverridePort stubPort(String key, String value) {
        return new ConfigOverridePort() {
            @Override
            public Map<String, OverrideEntry> overridesFor(String tenantId) {
                if (!TENANT.equals(tenantId)) {
                    return Map.of();
                }
                return Map.of(key, new OverrideEntry(
                        key, value, "INTEGER", Tightening.TIGHTEN,
                        "试点期收紧上限以控制成本", "admin-t", Instant.now()));
            }

            @Override
            public OverrideEntry apply(String t, String k, String v, String r, String a) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void clear(String t, String k, String a) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static WorkerExecutionConfig.RunExecutionInputs inputs(ConfigOverridePort port) {
        return new WorkerExecutionConfig.RunExecutionInputs(
                port == null ? null : new EffectiveConfigResolver(port),
                "2.00", "12", "20",
                Map.of("get_student_profile", ToolRiskLevel.READ),
                Map.of(ToolRiskLevel.WRITE_EXTERNAL, ApprovalRequirement.REQUIRED));
    }

    private static RunClaimPort.LeasedTask task() {
        return new RunClaimPort.LeasedTask(
                UUID.randomUUID().toString(), 1L, Instant.now().plusSeconds(60), 0);
    }

    @Test
    @DisplayName("✅ D15-9 覆盖值真的进入新 Run 的快照")
    void overrideReachesNewRunSnapshot() {
        var snapshot = inputs(stubPort("agent.run.max-steps", "7"))
                .snapshotFor(task(), TENANT);

        assertThat(snapshot.maxSteps())
                .as("⛔ 快照未使用覆盖值 = 「写入成功」但「实际不生效」")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("✅ D15-9b 未覆盖的键沿用 yml 默认（不被误清空）")
    void nonOverriddenKeysKeepYmlValue() {
        var snapshot = inputs(stubPort("agent.run.max-steps", "7"))
                .snapshotFor(task(), TENANT);

        assertThat(snapshot.maxCostPerRun())
                .as("⛔ 未覆盖的键被误改 = 写一个键影响了其它键")
                .isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(snapshot.maxLlmCalls()).isEqualTo(12);
    }

    @Test
    @DisplayName("✅ D15-9c 其他租户的覆盖不串味")
    void otherTenantOverrideNotApplied() {
        // 替身只对 TENANT 返回覆盖；用 OTHER_TENANT 取快照应拿到 yml 值
        var snapshot = inputs(stubPort("agent.run.max-steps", "7"))
                .snapshotFor(task(), OTHER_TENANT);
        assertThat(snapshot.maxSteps())
                .as("⛔ 跨租户配置串味 = A 租户的覆盖影响 B 租户")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("✅ D15-9d tenantId 为 null 时回落到 yml（不抛错）")
    void nullTenantFallsBackToYml() {
        var snapshot = inputs(stubPort("agent.run.max-steps", "7"))
                .snapshotFor(task(), null);
        assertThat(snapshot.maxSteps()).isEqualTo(20);
    }

    @Test
    @DisplayName("⛔ D15-9e 未装配覆盖端口时，行为与 P4-D15 之前逐位相同")
    void nullResolverBehavesAsBefore() {
        // ⚠️ 本用例等价于反证 S-4 注入后的状态：
        //    若有人把「读覆盖」从快照路径摘掉而没人发现，
        //    「写接口显示新值、快照仍是旧值」就会成为唯一行为。
        var snapshot = inputs(null).snapshotFor(task(), TENANT);
        assertThat(snapshot.maxSteps()).isEqualTo(20);
        assertThat(snapshot.maxCostPerRun()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(snapshot.maxLlmCalls()).isEqualTo(12);
    }

    @Test
    @DisplayName("✅ D15-9f 每次快照独立读取（无缓存 → 多实例一致）")
    void snapshotReadsFreshEachTime() {
        // 两次快照之间改变替身返回值，第二次必须反映新值。
        // ⚠️ 若将来给 EffectiveConfigResolver 加了本地缓存而未处理失效，
        //    本用例会红——那正是「实例 A 已生效、B 仍用旧值」的分裂（P4-D15 §6 L-3）。
        var mutable = new Object() {
            String value = "7";
        };
        ConfigOverridePort port = new ConfigOverridePort() {
            @Override
            public Map<String, OverrideEntry> overridesFor(String tenantId) {
                return Map.of("agent.run.max-steps", new OverrideEntry(
                        "agent.run.max-steps", mutable.value, "INTEGER",
                        Tightening.TIGHTEN, "r", "a", Instant.now()));
            }

            @Override
            public OverrideEntry apply(String t, String k, String v, String r, String a) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void clear(String t, String k, String a) {
                throw new UnsupportedOperationException();
            }
        };

        var inputs = inputs(port);
        RunConfigSnapshot first = inputs.snapshotFor(task(), TENANT);
        mutable.value = "9";
        RunConfigSnapshot second = inputs.snapshotFor(task(), TENANT);

        assertThat(first.maxSteps()).isEqualTo(7);
        assertThat(second.maxSteps())
                .as("⛔ 第二次快照读到旧值 = 存在未失效的本地缓存（多实例将分裂）")
                .isEqualTo(9);
    }
}