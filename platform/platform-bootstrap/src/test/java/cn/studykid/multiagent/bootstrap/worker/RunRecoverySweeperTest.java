package cn.studykid.multiagent.bootstrap.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort.PlatformTenantRow;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;

/**
 * 崩溃恢复扫描器单元测试（第二十一批 · 缺陷②修复 · 接通 claimTasks 生产调用方）。
 *
 * <p><b>本类要证明</b>：{@link RunRecoverySweeper#sweep()} 在三种条件下行为正确——
 * <ul>
 *   <li>禁用 → 完全不扫描；</li>
 *   <li>平台运维通道未接入（租户端口不可用）→ 仅跳过、不报错、不扫描；</li>
 *   <li>有活跃租户且存在过期 RUNNING → 真正接管并执行（孤儿 Run 被复活）。</li>
 * </ul>
 *
 * <p>修复前 {@code claimTasks} 没有任何生产调用方，worker 崩溃后其 RUNNING run
 * 永久卡死；本测试锁住「扫描器确实会把过期 RUNNING 驱动给执行器」这一行为。
 */
@DisplayName("RunRecoverySweeper · 崩溃恢复扫描器")
class RunRecoverySweeperTest {

    private static final String TENANT = "6f5d33fc-723e-4f64-beda-9e4435c72691";

    // ── 替身 ────────────────────────────────────────────────────────

    /** 只记录 claimTasks 调用、并按需返回过期 RUNNING 的领取端口。 */
    static final class FakeClaimPort implements RunClaimPort {
        final AtomicInteger claimTasksCount = new AtomicInteger();
        List<RunClaimPort.LeasedTask> toReturn = List.of();

        @Override public List<RunClaimPort.LeasedTask> claimTasks(
                String t, String w, Duration d, int l) {
            claimTasksCount.incrementAndGet();
            return toReturn;
        }
        @Override public java.util.Optional<RunClaimPort.LeasedTask> claimSpecificRun(
                String t, String r, String w, Duration d) { return java.util.Optional.empty(); }
        @Override public boolean renewLease(String t, String r, String w, long f, Duration d) { return true; }
        @Override public boolean releaseLease(String t, String r, String w, long f) { return true; }
        @Override public boolean commitResult(String t, String r, long f, String s, String p) { return true; }
        @Override public boolean isCurrentGeneration(String t, String r, long f) { return true; }
        @Override public boolean requestCancel(String t, String r) { return false; }
        @Override public boolean isCancelled(String t, String r) { return false; }
    }

    /** 返回脚本化租户列表的端口。 */
    static final class FakeTenantPort implements PlatformTenantPort {
        List<PlatformTenantRow> tenants = List.of();

        @Override public List<PlatformTenantRow> listTenants(String statusFilter, int limit) {
            return tenants;
        }
        @Override public PlatformTenantRow findTenant(String tenantId) { return null; }
        @Override public QuotaRow findQuota(String tenantId) { return null; }
        @Override public PlatformTenantRow provisionTenant(String slug, String name, String plan) {
            throw new UnsupportedOperationException();
        }
        @Override public PlatformTenantRow transitionLifecycle(String tenantId,
                String expectedCurrentState, String newState) { return null; }
        @Override public QuotaRow updateQuota(String tenantId, QuotaRow quota) { return null; }
        @Override public CapabilityGrantRow grantCapability(String tenantId,
                String resourceCode, String scope) { throw new UnsupportedOperationException(); }
        @Override public boolean revokeCapability(String tenantId, String resourceCode) { return false; }
        @Override public List<CapabilityGrantRow> listCapabilities(String tenantId) { return List.of(); }
    }

    /** 可返回 null（模拟「运维通道未接入」）的对象提供者。 */
    static final class FakeObjectProvider implements ObjectProvider<PlatformTenantPort> {
        private final PlatformTenantPort value;
        FakeObjectProvider(PlatformTenantPort v) { this.value = v; }
        @Override public PlatformTenantPort getObject(Object... args) throws BeansException { return value; }
        @Override public PlatformTenantPort getObject() throws BeansException { return value; }
        @Override public PlatformTenantPort getIfAvailable() throws BeansException { return value; }
        @Override public PlatformTenantPort getIfUnique() throws BeansException { return value; }
    }

    private static RunRecoverySweeper sweeper(boolean enabled, PlatformTenantPort tenantPort,
            RunClaimPort claimPort, RunDispatchConsumer.RunExecutor executor) {
        return new RunRecoverySweeper(claimPort, executor, new FakeObjectProvider(tenantPort),
                "worker-test", 30, enabled, 50, 200, false,
                // ⚠️ 挂起超时（秒）：M1-4 防挂起泄漏兜底用；测试里取默认值即可
                1800L);
    }

    private static RunClaimPort.LeasedTask overdue(String runId) {
        // 租约已过期 → claimTasks 会接管（扫描器只接管过期 RUNNING）
        return new RunClaimPort.LeasedTask(runId, 1L, Instant.now().minusSeconds(60), 1);
    }

    private static PlatformTenantRow activeTenant(String tenantId) {
        return new PlatformTenantRow(tenantId, "slug", "name", "ACTIVE", null,
                1, 1, Instant.now(), Instant.now());
    }

    // ── 行为断言 ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("缺陷②：扫描器必须真正接管过期 RUNNING")
    class RecoverySweeps {

        @Test
        @DisplayName("⚠️ 禁用时不调用 claimTasks（完全不扫描）")
        void disabledSkipsSweep() {
            FakeClaimPort claim = new FakeClaimPort();
            FakeTenantPort tenant = new FakeTenantPort();
            tenant.tenants = List.of(activeTenant(TENANT));

            sweeper(false, tenant, claim, (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED).sweep();

            assertThat(claim.claimTasksCount)
                    .as("⚠️ 禁用开关必须让扫描器完全静默，不得触碰任何租约")
                    .hasValue(0);
        }

        @Test
        @DisplayName("⚠️ 运维通道未接入（租户端口不可用）→ 跳过、不报错、不扫描")
        void tenantPortUnavailableSkips() {
            FakeClaimPort claim = new FakeClaimPort();
            // ObjectProvider 返回 null → getIfAvailable() 为 null（fail-closed：不强行枚举）
            var sw = new RunRecoverySweeper(claim,
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED,
                    new FakeObjectProvider(null),
                    "worker-test", 30, true, 50, 200, false,
                    // ⚠️ 挂起超时（秒）：M1-4 防挂起泄漏兜底用，测试取默认值
                    1800L);

            assertThat(sw.healthy()).as("扫描前尚无任何一轮被观测，视为健康").isTrue();
            sw.sweep();

            assertThat(claim.claimTasksCount)
                    .as("⚠️ 端口不可用时不得因缺依赖而崩，亦不得强行动用未验证数据源")
                    .hasValue(0);
        }

        @Test
        @DisplayName("⚠️⚠️ P3-1 ⑤：端口不可用必须体现为**健康降级**，而非仅一行被淹没的 WARN")
        void tenantPortUnavailableDegradesHealth() {
            // ⚠️ 修复前这里只有一行 WARN，且每 30 秒重复一次。
            //    一天 2880 行同文案日志会被告警规则当噪音滤掉 ——
            //    「有日志」不等于「可被告警」，实际等同于静默失效：
            //    worker 真的崩了之后，没人会发现恢复链路早已停摆。
            var sw = sweeper(true, null, new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);

            sw.sweep();

            assertThat(sw.healthy())
                    .as("崩溃恢复链路未生效时健康标记必须降级（供健康检查端点与外部告警读取）")
                    .isFalse();
            assertThat(sw.unhealthyReason())
                    .isNotBlank()
                    .as("降级原因必须写清是哪条依赖不可用，否则运维仍是从零排查")
                    .contains("PlatformTenantPort");
            assertThat(sw.portUnavailableStreak()).isEqualTo(1);
        }

        @Test
        @DisplayName("✅ 端口恢复可用 → 健康自动回升（降级不得是单向不可逆的）")
        void healthRecoversWhenPortReturns() {
            FakeTenantPort tenant = new FakeTenantPort();
            tenant.tenants = List.of(activeTenant(TENANT));
            var sw = sweeper(true, null, new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);
            sw.sweep();
            assertThat(sw.healthy()).isFalse();

            var restored = sweeper(true, tenant, new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);
            restored.sweep();

            assertThat(restored.healthy())
                    .as("恢复后必须回到健康态，否则健康标记会永久假红、最终被忽略")
                    .isTrue();
            assertThat(restored.portUnavailableStreak()).isZero();
        }

        @Test
        @DisplayName("⛔ require-tenant-port=true 且端口不可用 → 明确失败（不静默继续）")
        void requiredTenantPortMissingFailsLoudly() {
            var sw = new RunRecoverySweeper(new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED,
                    new FakeObjectProvider(null),
                    "worker-test", 30, true, 50, 200, true,
                    // ⚠️ 挂起超时（秒）：M1-4 防挂起泄漏兜底用，测试取默认值
                    1800L);

            assertThatThrownBy(sw::sweep)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("require-tenant-port")
                    .as("声明了必须有恢复能力就不能悄悄运行下去");
        }

        @Test
        @DisplayName("⚠️⚠️ 有活跃租户且存在过期 RUNNING → 接管并执行（孤儿 Run 被复活）")
        void recoversOverdueRunning() {
            FakeClaimPort claim = new FakeClaimPort();
            claim.toReturn = List.of(overdue("run-overdue-1"));
            FakeTenantPort tenant = new FakeTenantPort();
            tenant.tenants = List.of(activeTenant(TENANT));
            AtomicInteger executed = new AtomicInteger();

            sweeper(true, tenant, claim, (t, task) -> {
                executed.incrementAndGet();
                assertThat(t).isEqualTo(TENANT);
                assertThat(task.runId()).isEqualTo("run-overdue-1");
                return RunDispatchConsumer.Outcome.SUCCEEDED;
            }).sweep();

            assertThat(claim.claimTasksCount)
                    .as("⚠️ 必须真正枚举活跃租户并调用 claimTasks 接管过期 RUNNING")
                    .hasValue(1);
            assertThat(executed.get())
                    .as("⚠️ 接管的 Run 必须被驱动给执行器（与 MQ 主路径相同），否则仍卡死")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 单租户内某 Run 执行异常 → 不阻断同批其他 Run 接管")
        void executorExceptionIsolatedWithinTenant() {
            FakeClaimPort claim = new FakeClaimPort();
            claim.toReturn = List.of(overdue("run-ok"), overdue("run-bad"));
            FakeTenantPort tenant = new FakeTenantPort();
            tenant.tenants = List.of(activeTenant(TENANT));
            AtomicInteger okRan = new AtomicInteger();

            // ⚠️ 不得向外抛异常；run-bad 失败不影响 run-ok 继续接管
            sweeper(true, tenant, claim, (t, task) -> {
                if (task.runId().equals("run-bad")) {
                    throw new RuntimeException("模拟恢复执行失败");
                }
                okRan.incrementAndGet();
                return RunDispatchConsumer.Outcome.SUCCEEDED;
            }).sweep();

            assertThat(claim.claimTasksCount).hasValue(1);
            assertThat(okRan.get())
                    .as("⚠️ 异常隔离：正常 Run 仍须被接管执行，故障域不扩散")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 无活跃租户（listTenants 空）→ 不调用 claimTasks")
        void noActiveTenantsSkips() {
            FakeClaimPort claim = new FakeClaimPort();
            FakeTenantPort tenant = new FakeTenantPort();
            tenant.tenants = List.of(); // 空

            sweeper(true, tenant, claim, (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED).sweep();

            assertThat(claim.claimTasksCount).hasValue(0);
        }
    }

    /**
     * 租户枚举**抛异常**的降级路径（2026-10-31 实测缺陷的回归锁）。
     *
     * <p><b>⚠️ 这个缺陷是怎么发现的</b>：切 {@code app_agent} 角色后，
     * {@code platform} schema 没给应用角色 USAGE，
     * {@code RunRecoverySweeper} 每 30 秒抛一次
     * {@code permission denied for schema platform}。
     *
     * <p><b>⚠️ 为什么它比「端口不可用」更隐蔽</b>：
     * 「端口不可用」有专门的降级分支（置健康状态 + 抑制刷屏），
     * 而<b>枚举调用本身</b>当时裸奔在 try/catch 之外 ——
     * 异常一路抛到 Spring 的调度线程包装器，
     * 表现为「Unexpected error occurred in scheduled task」，
     * 而本类的健康状态<b>从未被置位</b>：
     * 「恢复链路已死」这件事<b>没有落到任何可告警的地方</b>，
     * 健康检查端点照样报 healthy。
     */
    @Nested
    @DisplayName("租户枚举抛异常（权限/连接/SQL 失败）→ 与端口不可用同等降级")
    class EnumerationFailureDegrades {

        /** 枚举时抛异常的端口替身（模拟 permission denied / 连接断）。 */
        static final class ThrowingTenantPort implements PlatformTenantPort {
            final RuntimeException failure;
            ThrowingTenantPort(RuntimeException f) { this.failure = f; }

            @Override public List<PlatformTenantRow> listTenants(String statusFilter, int limit) {
                throw failure;
            }
            @Override public PlatformTenantRow findTenant(String tenantId) { return null; }
            @Override public QuotaRow findQuota(String tenantId) { return null; }
            @Override public PlatformTenantRow provisionTenant(String s, String n, String p) {
                throw new UnsupportedOperationException();
            }
            @Override public PlatformTenantRow transitionLifecycle(String t,
                    String e, String n) { return null; }
            @Override public QuotaRow updateQuota(String t, QuotaRow q) { return null; }
            @Override public CapabilityGrantRow grantCapability(String t,
                    String r, String s) { throw new UnsupportedOperationException(); }
            @Override public boolean revokeCapability(String t, String r) { return false; }
            @Override public List<CapabilityGrantRow> listCapabilities(String t) { return List.of(); }
        }

        @Test
        @DisplayName("⚠️⚠️ 枚举抛异常 → 必须体现为健康降级（不得只抛给调度线程）")
        void enumerationFailureDegradesHealth() {
            var sw = sweeper(true,
                    new ThrowingTenantPort(new IllegalStateException(
                            "列出租户失败: ERROR: permission denied for schema platform")),
                    new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);

            // ⚠️ 不得把异常抛出去：那会让它落到 Spring 的调度线程包装器，
            //    表现为「Unexpected error occurred in scheduled task」，
            //    而健康状态从未被置位 —— 等于「有日志但不可被察觉」。
            sw.sweep();

            assertThat(sw.healthy())
                    .as("⚠️⚠️ 枚举失败 = 恢复链路已死，健康标记必须降级"
                            + "（否则健康端点照样报 healthy，运维无从察觉）")
                    .isFalse();
            assertThat(sw.unhealthyReason())
                    .isNotBlank()
                    .as("降级原因必须写清是租户枚举失败，并带上原始原因")
                    .contains("listTenants")
                    .contains("permission denied for schema platform");
            assertThat(sw.portUnavailableStreak()).isEqualTo(1);
        }

        @Test
        @DisplayName("⚠️ 枚举失败时不得触碰任何租约（不得在半失败状态下动数据）")
        void enumerationFailureDoesNotTouchLeases() {
            FakeClaimPort claim = new FakeClaimPort();
            var sw = sweeper(true,
                    new ThrowingTenantPort(new IllegalStateException("连不上库")),
                    claim,
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);

            sw.sweep();

            assertThat(claim.claimTasksCount)
                    .as("⚠️ 租户清单都没拿到就不得接管任何 Run（否则会把活租户的租约搅乱）")
                    .hasValue(0);
        }

        @Test
        @DisplayName("⛔ require-tenant-port=true 且枚举失败 → 明确失败（不静默继续）")
        void requiredTenantPortEnumerationFailureFailsLoudly() {
            var sw = new RunRecoverySweeper(new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED,
                    new FakeObjectProvider(new ThrowingTenantPort(
                            new IllegalStateException("permission denied"))),
                    "worker-test", 30, true, 50, 200, true,
                    1800L);

            assertThatThrownBy(sw::sweep)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("require-tenant-port")
                    .as("声明了必须有恢复能力就不能悄悄运行下去");
        }

        @Test
        @DisplayName("✅ 枚举恢复成功 → 健康自动回升（降级不得是单向不可逆的）")
        void healthRecoversWhenEnumerationSucceedsAgain() {
            var sw = sweeper(true,
                    new ThrowingTenantPort(new IllegalStateException("临时故障")),
                    new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);
            sw.sweep();
            assertThat(sw.healthy()).isFalse();

            FakeTenantPort tenant = new FakeTenantPort();
            tenant.tenants = List.of(activeTenant(TENANT));
            var restored = sweeper(true, tenant, new FakeClaimPort(),
                    (t, task) -> RunDispatchConsumer.Outcome.SUCCEEDED);
            restored.sweep();

            assertThat(restored.healthy())
                    .as("恢复后必须回到健康态，否则健康标记会永久假红、最终被忽略")
                    .isTrue();
            assertThat(restored.portUnavailableStreak()).isZero();
        }
    }
}
