package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ExecutionIntent;
import cn.studykid.multiagent.core.cap.ResultEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cap_* 仓储集成测试（W-PERM-P0-T4 / T5 · §6.1）。
 *
 * <p>覆盖：能力闭包快照、发布头、结果事件、执行意图的存储语义，
 * 以及全部租户级表的 RLS 隔离。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class CapRepositoryIT extends CapITBase {

    // ── execution_capability_snapshot ─────────────────────────────

    @Test
    @DisplayName("✅ 能力闭包快照可写可读，四版本齐备")
    void capabilitySnapshotRoundTrip() {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-snap-agent"), "{}", true);
        String snapshotId = createExecutionSnapshot(tenantA, agentId);
        String runId = createRun(tenantA, agentId, snapshotId);

        JdbcCapabilitySnapshotRepository repo =
                new JdbcCapabilitySnapshotRepository(appPool);
        CapabilitySnapshot snap = new CapabilitySnapshot(
                runId, tenantA, agentId, "{\"skills\":[]}", 1, 7L, "fp-1", 3L, Instant.now());

        assertThat(repo.save(snap)).as("首次写入应成功").isTrue();
        assertThat(repo.find(tenantA, runId)).isPresent();
        CapabilitySnapshot loaded = repo.find(tenantA, runId).orElseThrow();
        assertThat(loaded.configVersion()).isEqualTo(7L);
        assertThat(loaded.authzVersion()).isEqualTo(3L);
        assertThat(loaded.configFingerprint()).isEqualTo("fp-1");
    }

    @Test
    @DisplayName("✅ 快照不可变：重复保存同 runId 不覆盖（历史可追溯）")
    void capabilitySnapshotIsImmutable() {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-snap-imm"), "{}", true);
        String snapshotId = createExecutionSnapshot(tenantA, agentId);
        String runId = createRun(tenantA, agentId, snapshotId);

        JdbcCapabilitySnapshotRepository repo =
                new JdbcCapabilitySnapshotRepository(appPool);
        assertThat(repo.save(new CapabilitySnapshot(
                runId, tenantA, agentId, "{\"v\":1}", 1, 1L, "fp-first", 1L, Instant.now())))
                .isTrue();
        assertThat(repo.save(new CapabilitySnapshot(
                runId, tenantA, agentId, "{\"v\":2}", 2, 2L, "fp-second", 2L, Instant.now())))
                .as("⛔ 快照被覆盖：历史失真，事后无法解释当时按哪版配置执行")
                .isFalse();

        assertThat(repo.find(tenantA, runId).orElseThrow().configFingerprint())
                .isEqualTo("fp-first");
    }

    // ── cap_config_release ────────────────────────────────────────

    @Test
    @DisplayName("✅ 发布头可写可读，序号由序列分配且单调")
    void configReleaseRoundTrip() {
        JdbcConfigReleaseRepository repo = new JdbcConfigReleaseRepository(appPool);
        ConfigRelease saved = repo.save(ConfigRelease.pending(ConfigRelease.Scope.TENANT, tenantA,
                "[{\"type\":\"agent\",\"id\":\"a1\",\"version\":1}]",
                "agg-1", ConfigRelease.Status.PUBLISHED, "req-1", "app-1",
                Instant.now(), Instant.now()));

        assertThat(saved.configPublishSeq())
                .as("⛔ 发布序号未由序列分配")
                .isGreaterThan(0L);
        assertThat(repo.findByPublishSeq(tenantA, saved.configPublishSeq())).isPresent();
        assertThat(repo.findLatestPublished(ConfigRelease.Scope.TENANT, tenantA))
                .isPresent();
    }

    @Test
    @DisplayName("✅ 当前版本只认 PUBLISHED：更高序号的 PREPARING 不得被选中")
    void currentReleaseIgnoresNonPublished() {
        JdbcConfigReleaseRepository repo = new JdbcConfigReleaseRepository(appPool);
        ConfigRelease published = repo.save(ConfigRelease.pending(ConfigRelease.Scope.TENANT, tenantA, "[]", "agg-pub",
                ConfigRelease.Status.PUBLISHED, "req", "app", Instant.now(), Instant.now()));
        // ⚠️ 造一个**序号更大**但未发布的 release。
        //    若实现是「在全部行上取 max(seq)」，就会选中它 ——
        //    那会让运行期读到 PREPARING 的部分发布组合（§6.3 第 5 条明令禁止）。
        ConfigRelease preparing = repo.save(ConfigRelease.pending(ConfigRelease.Scope.TENANT, tenantA, "[]", "agg-prep",
                ConfigRelease.Status.PREPARING, "req", "app", Instant.now(), Instant.now()));
        assertThat(preparing.configPublishSeq())
                .as("夹具失效：PREPARING 的序号必须更大才能验证该语义")
                .isGreaterThan(published.configPublishSeq());

        ConfigRelease current = repo.findLatestPublished(
                ConfigRelease.Scope.TENANT, tenantA).orElseThrow();
        assertThat(current.status())
                .as("⛔ 当前版本选中了未发布行：运行期会读到部分发布组合")
                .isEqualTo(ConfigRelease.Status.PUBLISHED);
        assertThat(current.configPublishSeq()).isEqualTo(published.configPublishSeq());
    }

    @Test
    @DisplayName("✅ 回滚只作审计旁路：ROLLED_BACK 不参与当前版本解析")
    void rolledBackIsAuditOnly() {
        JdbcConfigReleaseRepository repo = new JdbcConfigReleaseRepository(appPool);
        ConfigRelease first = repo.save(ConfigRelease.pending(ConfigRelease.Scope.TENANT, tenantA, "[]", "agg-1",
                ConfigRelease.Status.PUBLISHED, "req", "app", Instant.now(), Instant.now()));
        // 造一条更高序号的 ROLLED_BACK 行（模拟「被回滚掉的旧 release」）
        adminExec("INSERT INTO cap_config_release (config_publish_seq, scope, tenant_id, "
                + "object_versions, status, requester_id, approver_id) VALUES ("
                + (first.configPublishSeq() + 100) + ", 'TENANT', '" + tenantA
                + "'::uuid, '[]'::jsonb, 'ROLLED_BACK', 'req', 'app')");

        ConfigRelease current = repo.findLatestPublished(
                ConfigRelease.Scope.TENANT, tenantA).orElseThrow();
        assertThat(current.status())
                .as("⛔ ROLLED_BACK 参与了当前版本解析")
                .isEqualTo(ConfigRelease.Status.PUBLISHED);
    }

    @Test
    @DisplayName("✅ 租户不得写平台级发布（越权发布全局配置）")
    void tenantCannotWritePlatformRelease() {
        JdbcConfigReleaseRepository repo = new JdbcConfigReleaseRepository(appPool);
        assertThatThrownBy(() -> repo.save(ConfigRelease.pending(ConfigRelease.Scope.PLATFORM, null, "[]", "agg",
                ConfigRelease.Status.PUBLISHED, "req", "app", Instant.now(), Instant.now())))
                .as("⛔ 租户角色竟能写平台级发布：等于任一租户可改全局配置")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("✅ 平台级发布对租户只读可见")
    void platformReleaseIsReadable() {
        adminExec("INSERT INTO cap_config_release (config_publish_seq, scope, tenant_id, "
                + "object_versions, aggregate_fingerprint, status, requester_id, approver_id) "
                + "VALUES (nextval('cap_config_publish_seq'), 'PLATFORM', NULL, '[]'::jsonb, "
                + "'agg-plat', 'PUBLISHED', 'req', 'app')");

        JdbcConfigReleaseRepository repo = new JdbcConfigReleaseRepository(appPool);
        assertThat(repo.findLatestPublished(ConfigRelease.Scope.PLATFORM, null))
                .as("平台级发布对租户不可见：平台对象版本将无法解析")
                .isPresent();
    }

    // ── cap_result_event ──────────────────────────────────────────

    @Test
    @DisplayName("✅ 结果事件幂等：同一 invocation 只保留首条（只落账不重执行的地基）")
    void resultEventIsIdempotent() {
        JdbcResultEventRepository repo = new JdbcResultEventRepository(appPool);
        ResultEvent first = new ResultEvent(
                uuid(), tenantA, null, "inv-1", "term-1",
                ResultEvent.Status.SUCCEEDED, "{\"ok\":true}", null, Instant.now());

        assertThat(repo.recordIfAbsent(first)).isTrue();
        assertThat(repo.recordIfAbsent(new ResultEvent(
                uuid(), tenantA, null, "inv-1", "term-2",
                ResultEvent.Status.FAILED, "{}", "ERR", Instant.now())))
                .as("⛔ 重复写入被接受：重试落账会退化成重跑副作用")
                .isFalse();

        ResultEvent stored = repo.findByInvocation(tenantA, "inv-1").orElseThrow();
        assertThat(stored.resultStatus())
                .as("⛔ 首条结果被覆盖：确定结果被改写，双维度语义失效")
                .isEqualTo(ResultEvent.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("✅ UNKNOWN 结果被标记为须对账")
    void unknownResultRequiresReconciliation() {
        JdbcResultEventRepository repo = new JdbcResultEventRepository(appPool);
        repo.recordIfAbsent(new ResultEvent(
                uuid(), tenantA, null, "inv-u", null,
                ResultEvent.Status.UNKNOWN, null, "EXEC_UNKNOWN", Instant.now()));

        ResultEvent stored = repo.findByInvocation(tenantA, "inv-u").orElseThrow();
        assertThat(stored.requiresReconciliation()).isTrue();
    }

    // ── cap_execution_intent ──────────────────────────────────────

    @Test
    @DisplayName("✅ 执行意图幂等记录：重复调用返回既有意图而非新建")
    void executionIntentRecordIsIdempotent() {
        JdbcExecutionIntentRepository repo = new JdbcExecutionIntentRepository(appPool);
        ExecutionIntent first = repo.record(new ExecutionIntent(
                uuid(), tenantA, null, "inv-i", "tool-x", 1,
                "fp-args", "apr-1", ExecutionIntent.IntentState.RESERVED,
                Instant.now(), Instant.now()));
        ExecutionIntent second = repo.record(new ExecutionIntent(
                uuid(), tenantA, null, "inv-i", "tool-x", 1,
                "fp-args", "apr-1", ExecutionIntent.IntentState.RESERVED,
                Instant.now(), Instant.now()));

        assertThat(second.intentId())
                .as("⛔ 重复记录生成了新意图：同一调用出现两条意图，恢复时无从判断")
                .isEqualTo(first.intentId());
    }

    @Test
    @DisplayName("✅ 状态迁移是 CAS 式的：原状态不匹配则拒绝（防重复消费）")
    void intentTransitionIsCompareAndSet() {
        JdbcExecutionIntentRepository repo = new JdbcExecutionIntentRepository(appPool);
        repo.record(new ExecutionIntent(
                uuid(), tenantA, null, "inv-c", "tool-x", 1,
                null, null, ExecutionIntent.IntentState.RESERVED,
                Instant.now(), Instant.now()));

        assertThat(repo.transitionState(tenantA, "inv-c",
                ExecutionIntent.IntentState.RESERVED,
                ExecutionIntent.IntentState.INTENT_SUBMITTED)).isTrue();

        assertThat(repo.transitionState(tenantA, "inv-c",
                ExecutionIntent.IntentState.RESERVED,
                ExecutionIntent.IntentState.CONSUMED))
                .as("⛔ 陈旧原状态仍能迁移：同一意图可被消费两次")
                .isFalse();
    }

    // ── RLS 隔离 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ RLS：非特权角色在 B 租户看不到 A 的全部 cap_* 行")
    void rlsIsolatesAllTenantTables() {
        String agentId = createAgentWithVersion(tenantA, uniqueName("cap-rls-agent"), "{}", true);
        String snapshotId = createExecutionSnapshot(tenantA, agentId);
        String runId = createRun(tenantA, agentId, snapshotId);

        JdbcCapabilitySnapshotRepository snapshots =
                new JdbcCapabilitySnapshotRepository(appPool);
        snapshots.save(new CapabilitySnapshot(
                runId, tenantA, agentId, "{}", 1, 1L, "fp", 1L, Instant.now()));

        JdbcResultEventRepository results = new JdbcResultEventRepository(appPool);
        results.recordIfAbsent(new ResultEvent(
                uuid(), tenantA, null, "inv-rls", null,
                ResultEvent.Status.SUCCEEDED, "{}", null, Instant.now()));

        JdbcExecutionIntentRepository intents = new JdbcExecutionIntentRepository(appPool);
        intents.record(new ExecutionIntent(
                uuid(), tenantA, null, "inv-rls", "tool-x", 1,
                null, null, ExecutionIntent.IntentState.RESERVED,
                Instant.now(), Instant.now()));

        for (String table : new String[]{"cap_agent_def", "cap_authz_version",
                "execution_capability_snapshot", "cap_result_event", "cap_execution_intent"}) {
            long visibleToB = appCountAsTenant(tenantB, "SELECT count(*) FROM " + table);
            assertThat(visibleToB)
                    .as("⛔ B 在 %s 中看到了 A 的行 = RLS 失效", table)
                    .isZero();
        }
    }
}
