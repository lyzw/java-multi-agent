package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishResult;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.core.cap.DecisionAuditEntry;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;
import cn.studykid.multiagent.ports.persistence.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原子发布与回滚服务的单测（P5-T1/T2/T3 · §6.3）。
 *
 * <p>⚠️ <b>本类锁的是「事务语义」而非返回值</b>：
 * CAS 失败后<b>整事务回滚、无半成品当前发布</b>（PUB-1），
 * 只有在<b>事务边界被真实模拟</b>（失败即撤销写入）时才能验证。
 * 若替身只是「记一笔日志」，这个断言就永远绿 —— 那是最坏的失效形态。
 */
class ConfigPublishServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 原子发布：版本行 + 发布头 + 指针 CAS 三者同时生效")
    void publishMovesPointerAndPersistsAllThree() {
        World world = new World();
        ConfigPublishService service = service(world);

        ConfigPublishResult result = service.publish(command());

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(world.tools).containsKey("t-a@1");
        assertThat(world.skills).containsKey("s-a@1");
        assertThat(world.releases.rows).hasSize(1);
        assertThat(world.pointer.currentSeq).isEqualTo(result.publishSeq());
        assertThat(world.pointer.currentReleaseId).isEqualTo(result.release().releaseId());
        assertThat(world.audit.entries).hasSize(1);
        assertThat(world.audit.entries.get(0).decisionKind())
                .isEqualTo(DecisionAuditEntry.Kind.PUBLISH);
    }

    @Test
    @DisplayName("⛔ PUB-1：CAS 失败 → 整事务回滚，无半成品当前发布")
    void casFailureRollsBackEverything() {
        World world = new World();
        world.pointer.failAllCas = true;
        ConfigPublishService service = service(world);

        // ⚠️ CAS 持续失败 → 变基重试耗尽后抛冲突
        assertThatThrownBy(() -> service.publish(command()))
                .isInstanceOf(ConfigPublishConflictException.class);

        assertThat(world.tools)
                .as("⛔ 版本行未被回滚：CAS 失败后仍留下已插入的版本行（半成品当前发布）")
                .isEmpty();
        assertThat(world.skills).isEmpty();
        assertThat(world.releases.rows)
                .as("⛔ 发布头未被回滚：CAS 失败后仍留下发布记录")
                .isEmpty();
        assertThat(world.pointer.currentSeq)
                .as("⛔ 指针被移动：CAS 失败却改了当前发布")
                .isNull();
    }

    @Test
    @DisplayName("✅ §6.3 第 4 条：同基线并发 → 失败方变基重试后成功")
    void casConflictTriggersRebaseRetry() {
        World world = new World();
        world.pointer.failNextCas = true;
        ConfigPublishService service = service(world);

        ConfigPublishResult result = service.publish(command());

        assertThat(result.attempts())
                .as("⛔ 未发生变基重试：并发发布失败方应读新基线再试（§6.3 第 4 条）")
                .isEqualTo(2);
        assertThat(world.pointer.currentSeq).isEqualTo(result.publishSeq());
        assertThat(world.tools).containsKey("t-a@1");
    }

    @Test
    @DisplayName("✅ 配置非法时门禁在事务之外拒绝：不占序号、不写任何行")
    void invalidConfigIsRejectedBeforeAnyWrite() {
        World world = new World();
        ConfigPublishService service = service(world);

        ConfigPublishCommand bad = new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null,
                null, "r", "a", null,
                List.of(toolWithRisk("t-bad", CapabilityRiskLevel.UNKNOWN)),
                List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.publish(bad))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .satisfies(e -> assertThat(((ConfigPublishRejectedException) e).reasonCode())
                        .isEqualTo(ReasonCode.RISK_LEVEL_UNKNOWN));

        assertThat(world.releases.rows).isEmpty();
        assertThat(world.pointer.currentSeq).isNull();
        assertThat(world.seq)
                .as("⛔ 门禁失败仍消耗了发布序号：序号是可追溯的发布事件编号，不该被无效发布占用")
                .isZero();
    }

    @Test
    @DisplayName("✅ 回滚：新建回滚发布记录 + 指针切换 + 原发布标 ROLLED_BACK")
    void rollbackCreatesNewReleaseAndSwitchesPointer() {
        World world = new World();
        ConfigPublishService service = service(world);

        ConfigPublishResult first = service.publish(command());
        ConfigPublishResult second = service.publish(commandWithVersion(2));
        assertThat(world.pointer.currentSeq).isEqualTo(second.publishSeq());

        ConfigPublishResult rollback = service.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.PLATFORM, null, first.release().releaseId(),
                null, "r", "a", null, null));

        // ① 新建了一条 PUBLISHED 的回滚发布记录，其 object_versions 指向目标发布
        assertThat(world.releases.rows).hasSize(3);
        ConfigRelease rollbackRow = world.releases.rows.get(2);
        assertThat(rollbackRow.status()).isEqualTo(ConfigRelease.Status.PUBLISHED);
        assertThat(rollbackRow.objectVersions())
                .as("⛔ 回滚发布的对象集合不是目标发布的：回滚没有真正回到旧版本")
                .isEqualTo(first.release().objectVersions());
        assertThat(rollbackRow.configPublishSeq()).isGreaterThan(second.publishSeq());

        // ② 原发布被标记为 ROLLED_BACK（仅审计旁路）
        assertThat(world.releases.rows.get(1).status())
                .as("⛔ 被回滚的发布未被标记 ROLLED_BACK：审计旁路缺失")
                .isEqualTo(ConfigRelease.Status.ROLLED_BACK);

        // ③ 指针切到回滚发布
        assertThat(world.pointer.currentReleaseId).isEqualTo(rollback.release().releaseId());
        assertThat(world.pointer.currentSeq).isEqualTo(rollback.publishSeq());

        // ④ 回滚本身也留下审计
        assertThat(world.audit.entries).hasSize(3);
        assertThat(world.audit.entries.get(2).decisionKind())
                .isEqualTo(DecisionAuditEntry.Kind.ROLLBACK);
    }

    @Test
    @DisplayName("⛔ 回滚到当前发布被拒（无意义的回滚）")
    void rollbackToCurrentIsRejected() {
        World world = new World();
        ConfigPublishService service = service(world);
        ConfigPublishResult first = service.publish(command());

        assertThatThrownBy(() -> service.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.PLATFORM, null, first.release().releaseId(),
                null, "r", "a", null, null)))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("无需回滚");
    }

    @Test
    @DisplayName("⛔ 回滚遇到基线冲突直接失败，不自动变基（否则会回滚掉另一份配置）")
    void rollbackDoesNotRebase() {
        World world = new World();
        ConfigPublishService service = service(world);
        ConfigPublishResult first = service.publish(command());
        // 发布第二版，使「当前发布」与「回滚目标」不同
        service.publish(commandWithVersion(2));

        // ⚠️ 模拟「服务读完基线之后、真正 CAS 之前，另一个发布者抢先提交」
        world.pointer.moveOnNextCas = true;

        assertThatThrownBy(() -> service.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.PLATFORM, null, first.release().releaseId(),
                null, "r", "a", null, null)))
                .isInstanceOf(ConfigPublishConflictException.class);

        assertThat(world.releases.rows)
                .as("⛔ 回滚失败却留下了发布记录：回滚的写没有随事务回滚")
                .hasSize(2);
        assertThat(world.audit.entries)
                .as("⛔ 回滚失败却留下了审计记录：审计写入没有随事务回滚")
                .hasSize(2);
        assertThat(world.pointer.currentSeq)
                .as("⛔ 回滚失败却改了当前指针")
                .isEqualTo(999L);
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static ConfigPublishService service(World world) {
        return new ConfigPublishService(
                world.tx, world.pointer, world.releases,
                world.toolDefs, world.skillDefs, world.serverDefs, world.agentDefs,
                world.audit, new ConfigValidationGate(), CLOCK);
    }

    private static ConfigPublishCommand command() {
        return commandWithVersion(1);
    }

    private static ConfigPublishCommand commandWithVersion(int version) {
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, null,
                "requester-1", "approver-1", null,
                List.of(toolAt("t-a", version)), List.of(skill("s-a", version)), List.of(), List.of(),
                null);
    }

    private static CapToolDef toolAt(String id, int version) {
        return new CapToolDef(id, version, id, CapToolKind.NATIVE, CapabilityRiskLevel.READ,
                null, null, "{}", "{}", "fp-" + id, 1, CLOCK.instant(), null);
    }

    private static CapToolDef toolWithRisk(String id, CapabilityRiskLevel risk) {
        return new CapToolDef(id, 1, id, CapToolKind.NATIVE, risk,
                null, null, "{}", "{}", "fp-" + id, 1, CLOCK.instant(), null);
    }

    private static CapSkillDef skill(String id, int version) {
        return new CapSkillDef(id, version, id, null, List.of("t-a"), List.of(),
                CapabilityRiskLevel.READ, "{}", List.of(), "fp-" + id, 1, CLOCK.instant(), null);
    }

    /**
     * 可回滚的内存世界。
     *
     * <p>⚠️ <b>事务是真的会撤销写入的</b>：每次写入都登记一条撤销动作，
     * 事务失败时按相反顺序执行。这让 PUB-1 的断言真正有意义。
     */
    private static final class World {
        final Map<String, CapToolDef> tools = new LinkedHashMap<>();
        final Map<String, CapSkillDef> skills = new LinkedHashMap<>();
        final Deque<Runnable> undo = new LinkedList<>();
        long seq;
        final FakePointer pointer = new FakePointer(this);
        final FakeReleases releases = new FakeReleases(this);
        final FakeAudit audit = new FakeAudit(this);
        final ConfigPublishTransaction tx = new ConfigPublishTransaction() {
            @Override
            public <T> T execute(String tenantId, Action<T> action) {
                int mark = undo.size();
                try {
                    T result = action.run();
                    // 提交：清空撤销栈
                    undo.clear();
                    return result;
                } catch (RuntimeException e) {
                    // ⚠️ 回滚：按相反顺序撤销本次事务内的全部写入
                    while (undo.size() > mark) {
                        undo.pop().run();
                    }
                    throw e;
                }
            }
        };
        final CapToolDefPort toolDefs = new CapToolDefPort() {
            @Override
            public boolean insert(CapToolDef def) {
                String key = def.id() + "@" + def.version();
                if (tools.containsKey(key)) {
                    return false;
                }
                tools.put(key, def);
                undo.push(() -> tools.remove(key));
                return true;
            }

            @Override
            public Optional<CapToolDef> find(String toolId, int version) {
                return Optional.ofNullable(tools.get(toolId + "@" + version));
            }

            @Override
            public Optional<CapToolDef> findLatestPublished(String toolId) {
                return Optional.empty();
            }

            @Override
            public Optional<CapToolDef> findByMcpBinding(String mcpServerId, String mcpToolName) {
                return Optional.empty();
            }

            @Override
            public List<CapToolDef> listAll() {
                return List.copyOf(tools.values());
            }
        };
        final CapSkillDefPort skillDefs = new CapSkillDefPort() {
            @Override
            public boolean insert(CapSkillDef def) {
                String key = def.id() + "@" + def.version();
                if (skills.containsKey(key)) {
                    return false;
                }
                skills.put(key, def);
                undo.push(() -> skills.remove(key));
                return true;
            }

            @Override
            public Optional<CapSkillDef> find(String skillId, int version) {
                return Optional.ofNullable(skills.get(skillId + "@" + version));
            }

            @Override
            public Optional<CapSkillDef> findLatestPublished(String skillId) {
                return Optional.empty();
            }

            @Override
            public List<CapSkillDef> listAll() {
                return List.copyOf(skills.values());
            }
        };
        final CapMcpServerDefPort serverDefs = new CapMcpServerDefPort() {
            @Override
            public boolean insert(cn.studykid.multiagent.core.cap.CapMcpServerDef def) {
                return true;
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapMcpServerDef> find(String id,
                                                                                   int version) {
                return Optional.empty();
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapMcpServerDef> findLatestPublished(
                    String id) {
                return Optional.empty();
            }

            @Override
            public java.util.List<cn.studykid.multiagent.core.cap.CapMcpServerDef> listAll() {
                return java.util.List.of();
            }
        };
        final CapAgentDefPort agentDefs = new CapAgentDefPort() {
            @Override
            public boolean insert(cn.studykid.multiagent.core.cap.CapAgentDef def) {
                return true;
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapAgentDef> find(String tenantId,
                                                                                  String agentId,
                                                                                  int version) {
                return Optional.empty();
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapAgentDef> findLatest(
                    String tenantId, String agentId) {
                return Optional.empty();
            }

            @Override
            public Optional<cn.studykid.multiagent.core.cap.CapAgentDef> findLatestPublished(
                    String tenantId, String agentId) {
                return Optional.empty();
            }

            @Override
            public java.util.List<cn.studykid.multiagent.core.cap.CapAgentDef> listLatestPerAgent(
                    String tenantId) {
                return java.util.List.of();
            }
        };
    }

    private static final class FakePointer implements ConfigPointerPort {
        private final World world;
        Long currentSeq;
        Long currentReleaseId;
        /** 置为 true 时下一次 CAS 必定失败（模拟并发抢占）。 */
        boolean failNextCas;
        /** 置为 true 时所有 CAS 都失败（模拟持续并发冲突）。 */
        boolean failAllCas;
        /**
         * 置为 true 时，下一次 CAS <b>先</b>把指针推到别处（模拟「调用方读完基线后
         * 另一个发布者抢先提交」），再按基线比对 —— 于是基线不符、CAS 失败。
         */
        boolean moveOnNextCas;

        FakePointer(World world) {
            this.world = world;
        }

        @Override
        public Optional<ConfigPointer> current(ConfigRelease.Scope scope, String tenantId) {
            if (currentSeq == null) {
                return Optional.empty();
            }
            return Optional.of(new ConfigPointer("PLATFORM", scope, null,
                    currentReleaseId, currentSeq, CLOCK.instant()));
        }

        @Override
        public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                     Long expectedPublishSeq, long newReleaseId,
                                     long newPublishSeq) {
            if (moveOnNextCas) {
                moveOnNextCas = false;
                currentSeq = 999L;
                currentReleaseId = 999L;
            }
            if (failAllCas) {
                return false;
            }
            if (failNextCas) {
                failNextCas = false;
                return false;
            }
            if (!Objects.equals(currentSeq, expectedPublishSeq)) {
                return false;
            }
            Long oldSeq = currentSeq;
            Long oldReleaseId = currentReleaseId;
            currentSeq = newPublishSeq;
            currentReleaseId = newReleaseId;
            world.undo.push(() -> {
                currentSeq = oldSeq;
                currentReleaseId = oldReleaseId;
            });
            return true;
        }
    }

    private static final class FakeReleases implements ConfigReleasePort {
        private final World world;
        final List<ConfigRelease> rows = new ArrayList<>();
        private long nextReleaseId = 1;

        FakeReleases(World world) {
            this.world = world;
        }

        @Override
        public ConfigRelease save(ConfigRelease release) {
            long seq = ++world.seq;
            ConfigRelease saved = new ConfigRelease(nextReleaseId++, seq, release.scope(),
                    release.tenantId(), release.objectVersions(), release.aggregateFingerprint(),
                    release.status(), release.requesterId(), release.approverId(),
                    release.effectiveAt(), release.createdAt());
            rows.add(saved);
            world.undo.push(() -> rows.remove(saved));
            return saved;
        }

        @Override
        public Optional<ConfigRelease> findByReleaseId(String tenantId, long releaseId) {
            return rows.stream().filter(r -> r.releaseId() == releaseId).findFirst();
        }

        @Override
        public boolean markRolledBack(String tenantId, long releaseId) {
            for (int i = 0; i < rows.size(); i++) {
                ConfigRelease row = rows.get(i);
                if (row.releaseId() == releaseId) {
                    ConfigRelease marked = new ConfigRelease(row.releaseId(), row.configPublishSeq(),
                            row.scope(), row.tenantId(), row.objectVersions(),
                            row.aggregateFingerprint(), ConfigRelease.Status.ROLLED_BACK,
                            row.requesterId(), row.approverId(), row.effectiveAt(), row.createdAt());
                    rows.set(i, marked);
                    // ⚠️ for 循环变量不是事实上不可变，必须先复制一份 final 副本
                    final int index = i;
                    world.undo.push(() -> rows.set(index, row));
                    return true;
                }
            }
            return false;
        }

        @Override
        public Optional<ConfigRelease> findByPublishSeq(String tenantId, long configPublishSeq) {
            return rows.stream().filter(r -> r.configPublishSeq() == configPublishSeq).findFirst();
        }

        @Override
        public Optional<ConfigRelease> findLatestPublished(ConfigRelease.Scope scope, String tenantId) {
            return rows.stream()
                    .filter(r -> r.scope() == scope && r.status() == ConfigRelease.Status.PUBLISHED)
                    .reduce((a, b) -> b);
        }

        @Override
        public Page<ConfigRelease> listReleases(String tenantId, String cursor, int limit) {
            return Page.of(rows.stream()
                    .filter(r -> tenantId.equals(r.tenantId()))
                    .toList(), null);
        }
    }

    private static final class FakeAudit implements DecisionAuditPort {
        private final World world;
        final List<DecisionAuditEntry> entries = new ArrayList<>();

        FakeAudit(World world) {
            this.world = world;
        }

        @Override
        public void append(DecisionAuditEntry entry) {
            entries.add(entry);
            // ⚠️ 审计写入同样在事务内：CAS 失败时必须随事务一起撤销，
            //    否则会留下一条「其实没发布成功」的发布审计记录。
            world.undo.push(() -> entries.remove(entry));
        }

        @Override
        public List<DecisionAuditEntry> findByInvocation(String tenantId, String invocationId) {
            return List.of();
        }

        @Override
        public List<DecisionAuditEntry> findByRun(String tenantId, String runId) {
            return List.of();
        }

        @Override
        public cn.studykid.multiagent.core.cap.DecisionAuditCoverage coverage(String tenantId) {
            return new cn.studykid.multiagent.core.cap.DecisionAuditCoverage(
                    entries.size(), entries.size(), 0, 0, 0);
        }

        @Override
        public List<DecisionAuditEntry> findRecentDenies(String tenantId,
                                                         java.time.Instant since,
                                                         int limit) {
            return entries.stream()
                    .filter(e -> e.authzDecision()
                            == cn.studykid.multiagent.core.cap.AuthorizationDecision
                                    .Authorization.DENY)
                    .filter(e -> !e.createdAt().isBefore(since))
                    .toList();
        }
    }
}
