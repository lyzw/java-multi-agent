package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;

/**
 * 执行快照的 {@code config_version} 回填（S0 · G-3）。
 *
 * <p><b>⚠️ 本类存在的唯一理由</b>：修复前这里<b>无条件写 {@code 0L}</b>，
 * 于是「配置变过没有」这个判据在<b>已经发布过</b>的租户身上照样不成立 ——
 * 决策快照与真实策略版本从此脱钩，事后无法解释「这一版策略到底生不生效」。
 *
 * <p><b>本类的判据</b>：
 * <ul>
 *   <li>V-1：指针有当前发布 → 写指针的 {@code current_publish_seq}；</li>
 *   <li>V-2：租户尚未发布过（指针空）→ 写 0 并<b>计数</b>（0 的语义是「无可追溯序号」，
 *       不是「没查」）；</li>
 *   <li>V-3：指针读失败 → <b>整条快照都不写</b>（快照不可覆盖，宁缺毋滥），
 *       且异常不外抛（fail-soft）；</li>
 *   <li>V-4：连续两次发布 → 两份快照的 configVersion <b>递增</b>
 *       （文档 §8 G-3 的验收判据）。</li>
 * </ul>
 */
class CapDualWriteServiceConfigVersionTest {

    private static final String TENANT = "tenant-1";
    private static final String RUN = "run-1";
    private static final String AGENT = "agent-1";
    private static final String PRINCIPAL = "principal-1";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    // ── 替身 ────────────────────────────────────────────────────────

    /** 只记录写入的快照仓储。 */
    private static final class RecordingSnapshots implements CapabilitySnapshotPort {
        final List<CapabilitySnapshot> saved = new ArrayList<>();

        @Override
        public boolean save(@NonNull CapabilitySnapshot snapshot) {
            saved.add(snapshot);
            return true;
        }

        @Override
        public @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId,
                                                          @NonNull String runId) {
            return Optional.empty();
        }
    }

    /** 可编程的当前发布指针。 */
    private static final class StubPointers implements ConfigPointerPort {
        @Nullable Long currentPublishSeq;
        boolean fail;

        @Override
        public @NonNull Optional<ConfigPointer> current(ConfigRelease.@NonNull Scope scope,
                                                        @Nullable String tenantId) {
            if (fail) {
                throw new IllegalStateException("注入：指针读取失败");
            }
            if (currentPublishSeq == null) {
                return Optional.empty();
            }
            return Optional.of(new ConfigPointer(
                    ConfigPointer.pointerKey(scope, tenantId), scope, tenantId,
                    1L, currentPublishSeq, Instant.EPOCH));
        }

        @Override
        public boolean compareAndSet(ConfigRelease.@NonNull Scope scope, @Nullable String tenantId,
                                     @Nullable Long expectedPublishSeq,
                                     long newReleaseId, long newPublishSeq) {
            throw new UnsupportedOperationException("双写只读指针");
        }
    }

    /** LEAF 闭包的解析器替身。 */
    private static final class LeafResolver implements ClosureResolver {
        @Override
        public @NonNull EffectiveClosure resolve(@NonNull String tenantId, @NonNull String agentId,
                                                 int agentVersion) {
            return new EffectiveClosure(agentId, agentVersion, List.of(), List.of(), List.of());
        }

        @Override
        public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                        @NonNull String agentId) {
            return resolve(tenantId, agentId, 1);
        }
    }

    /** 授权版本替身：恒返回初始版本。 */
    private static final class FixedAuthz implements AuthzVersionPort {
        @Override
        public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                     @NonNull String principalId) {
            return Optional.of(version());
        }

        @Override
        public @NonNull AuthzVersion require(@NonNull String tenantId,
                                              @NonNull String principalId) {
            return version();
        }

        @Override
        public @NonNull AuthzVersion initialize(@NonNull String tenantId,
                                                 @NonNull String principalId) {
            return version();
        }

        @Override
        public long bump(@NonNull String tenantId, @NonNull String principalId) {
            return 1L;
        }

        @Override
        public @NonNull AuthzWatermark watermark(@NonNull String tenantId) {
            throw new UnsupportedOperationException("本测试不读水位");
        }

        private AuthzVersion version() {
            return new AuthzVersion(TENANT, PRINCIPAL, AuthzVersion.INITIAL_VERSION, Instant.EPOCH);
        }
    }

    /** 双写用不到的端口替身（仅满足构造）。 */
    private static final CapAgentDefPort UNUSED_AGENT_DEFS = new CapAgentDefPort() {
        @Override
        public boolean insert(@NonNull CapAgentDef def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                   @NonNull String agentId, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                         @NonNull String agentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                   @NonNull String agentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
            throw new UnsupportedOperationException();
        }
    };

    private static CapDualWriteService service(RecordingSnapshots snapshots,
                                               @Nullable ConfigPointerPort pointers) {
        return new CapDualWriteService(new CapDualWriteSwitch(true), UNUSED_AGENT_DEFS,
                new LeafResolver(), new FixedAuthz(), snapshots, CLOCK, pointers);
    }

    // ── V-1 / V-2 ───────────────────────────────────────────────────

    @Test
    @DisplayName("V-1 ✅ 指针有当前发布 → config_version 写指针指向的发布序号")
    void configVersionComesFromCurrentPointer() {
        RecordingSnapshots snapshots = new RecordingSnapshots();
        StubPointers pointers = new StubPointers();
        pointers.currentPublishSeq = 42L;

        service(snapshots, pointers).onExecutionSnapshotCreated(
                TENANT, RUN, AGENT, 3, PRINCIPAL);

        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).configVersion())
                .as("⛔ 仍写 0 = G-3 未修复：快照与真实配置版本脱钩")
                .isEqualTo(42L);
        assertThat(snapshots.saved.get(0).agentVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("V-2 ✅ 租户尚未发布过 → 写 0 并计数（0 = 无可追溯序号，不是「没查」）")
    void emptyPointerWritesZeroAndCounts() {
        RecordingSnapshots snapshots = new RecordingSnapshots();
        CapDualWriteService service = service(snapshots, new StubPointers());

        service.onExecutionSnapshotCreated(TENANT, RUN, AGENT, 1, PRINCIPAL);

        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).configVersion()).isZero();
        assertThat(service.unknownConfigVersionCount())
                .as("⚠️ 必须可观测：该计数持续增长说明发布链路没接上，追溯字段形同虚设")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("V-2b ✅ 未装配指针端口 → 写 0 并计数，不抛异常")
    void missingPointerPortWritesZeroAndCounts() {
        RecordingSnapshots snapshots = new RecordingSnapshots();
        CapDualWriteService service = service(snapshots, null);

        service.onExecutionSnapshotCreated(TENANT, RUN, AGENT, 1, PRINCIPAL);

        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).configVersion()).isZero();
        assertThat(service.unknownConfigVersionCount()).isEqualTo(1L);
    }

    // ── V-3 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("V-3 ⛔ 指针读失败 → 整条快照都不写（快照不可覆盖），且不外抛")
    void pointerReadFailureSkipsSnapshotEntirely() {
        RecordingSnapshots snapshots = new RecordingSnapshots();
        StubPointers pointers = new StubPointers();
        pointers.currentPublishSeq = 42L;
        pointers.fail = true;
        CapDualWriteService service = service(snapshots, pointers);

        service.onExecutionSnapshotCreated(TENANT, RUN, AGENT, 1, PRINCIPAL);

        assertThat(snapshots.saved)
                .as("⛔ 凭一个来路不明的序号写一份**不可覆盖**的历史，等于把失真固化进审计")
                .isEmpty();
        assertThat(service.deferredSnapshotCount()).isEqualTo(1L);
    }

    // ── V-4 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("V-4 ✅ 连续两次发布 → 两份快照的 config_version 递增（§8 G-3 验收判据）")
    void configVersionIncrementsAcrossReleases() {
        RecordingSnapshots snapshots = new RecordingSnapshots();
        StubPointers pointers = new StubPointers();
        CapDualWriteService service = service(snapshots, pointers);

        pointers.currentPublishSeq = 7L;
        service.onExecutionSnapshotCreated(TENANT, "run-7", AGENT, 1, PRINCIPAL);
        pointers.currentPublishSeq = 8L;
        service.onExecutionSnapshotCreated(TENANT, "run-8", AGENT, 1, PRINCIPAL);

        assertThat(snapshots.saved).extracting(CapabilitySnapshot::configVersion)
                .containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("✅ 双写开关关闭 → 一律不写（回退语义）")
    void disabledSwitchWritesNothing() {
        RecordingSnapshots snapshots = new RecordingSnapshots();
        StubPointers pointers = new StubPointers();
        pointers.currentPublishSeq = 42L;
        CapDualWriteService service = new CapDualWriteService(
                new CapDualWriteSwitch(false), UNUSED_AGENT_DEFS, new LeafResolver(),
                new FixedAuthz(), snapshots, CLOCK, pointers);

        service.onExecutionSnapshotCreated(TENANT, RUN, AGENT, 1, PRINCIPAL);

        assertThat(snapshots.saved).isEmpty();
    }
}
