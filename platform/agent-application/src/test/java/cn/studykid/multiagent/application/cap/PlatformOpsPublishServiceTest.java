package cn.studykid.multiagent.application.cap;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.core.cap.DecisionAuditCoverage;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台运维发布入口的单测（P6R1）。
 *
 * <p>⚠️ <b>本类锁的是「运维凭据的唯一可达路径」这条性质</b>：
 * <ol>
 *   <li><b>租户作用域的发布被拒绝，且运维事务从未被调用</b> ——
 *       闸门必须在任何凭据使用<b>之前</b>，否则「先拿凭据再判作用域」
 *       等于凭据已经外泄；</li>
 *   <li><b>平台作用域的发布确实走运维事务</b>，
 *       且事务收到的是 {@code tenantId = null}（PLATFORM 哨兵语义）。</li>
 * </ol>
 *
 * <p>⚠️ 本类<b>不</b>验证发布协议本身（原子性、CAS、回滚语义）——
 * 那些由 {@code ConfigPublishServiceTest} 与 {@code ConfigPublishIT} 负责；
 * 本类只回答一个问题：<b>哪条路径能碰到运维凭据</b>。
 */
class PlatformOpsPublishServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
            ZoneOffset.UTC);

    /** 事务替身：记录被调用的租户标识后抛出标记异常，避免真正落库。 */
    private static final class RecordingTransaction implements ConfigPublishTransaction {
        private int invocations;
        private String lastTenantId;

        @Override
        public <T> T execute(String tenantId, Action<T> action) {
            invocations++;
            lastTenantId = tenantId;
            throw new TxReached();
        }
    }

    private static final class TxReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⛔ 租户作用域的发布被拒绝，且运维事务从未被调用")
    void tenantScopeIsRejectedBeforeAnyCredentialUse() {
        RecordingTransaction tx = new RecordingTransaction();
        PlatformOpsPublishService service = service(tx);

        assertThatThrownBy(() -> service.publish(tenantCommand()))
                .isInstanceOf(ConfigPublishRejectedException.class)
                .hasMessageContaining("PLATFORM");

        assertThat(tx.invocations)
                .as("⛔ 运维事务被租户作用域的调用触发了："
                        + "凭据先于作用域判定被使用，闸门形同虚设")
                .isZero();
    }

    @Test
    @DisplayName("⛔ 租户作用域的回滚同样被拒绝")
    void tenantScopeRollbackIsRejected() {
        RecordingTransaction tx = new RecordingTransaction();
        PlatformOpsPublishService service = service(tx);

        assertThatThrownBy(() -> service.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.TENANT, "11111111-1111-1111-1111-111111111111",
                1L, null, "r", "a", null, null)))
                .isInstanceOf(ConfigPublishRejectedException.class);

        assertThat(tx.invocations).isZero();
    }

    @Test
    @DisplayName("✅ 平台作用域的发布走运维事务，且事务收到 PLATFORM 语义（tenantId=null）")
    void platformScopeReachesOpsTransaction() {
        RecordingTransaction tx = new RecordingTransaction();
        PlatformOpsPublishService service = service(tx);

        assertThatThrownBy(() -> service.publish(platformCommand()))
                .as("⚠️ 平台作用域的发布没有触达运维事务：平台级发布通道未接通")
                .isInstanceOf(TxReached.class);

        assertThat(tx.invocations).isEqualTo(1);
        assertThat(tx.lastTenantId)
                .as("⛔ 运维事务收到非 null 的租户标识："
                        + "运维通道写的是平台级行，租户标识必须为 null")
                .isNull();
    }

    @Test
    @DisplayName("✅ 平台作用域的回滚也走运维事务")
    void platformScopeRollbackReachesOpsTransaction() {
        RecordingTransaction tx = new RecordingTransaction();
        PlatformOpsPublishService service = service(tx);

        assertThatThrownBy(() -> service.rollback(new ConfigRollbackCommand(
                ConfigRelease.Scope.PLATFORM, null, 1L, null, "r", "a", null, null)))
                .isInstanceOf(TxReached.class);

        assertThat(tx.invocations).isEqualTo(1);
        assertThat(tx.lastTenantId).isNull();
    }

    @Test
    @DisplayName("✅ 拒绝原因码取自既有作用域不一致族（RESOURCE_UNKNOWN）")
    void rejectionUsesScopeReasonCode() {
        PlatformOpsPublishService service = service(new RecordingTransaction());

        assertThatThrownBy(() -> service.publish(tenantCommand()))
                .isInstanceOfSatisfying(ConfigPublishRejectedException.class, e ->
                        assertThat(e.reasonCode()).isEqualTo(ReasonCode.RESOURCE_UNKNOWN));
    }

    // ── 夹具 ──────────────────────────────────────────────────────

    private static PlatformOpsPublishService service(ConfigPublishTransaction tx) {
        return new PlatformOpsPublishService(tx, unusedPointer(), unusedReleases(),
                unusedTools(), unusedSkills(), unusedServers(), unusedAgents(), unusedAudit(),
                new ConfigValidationGate(), CLOCK);
    }

    private static ConfigPublishCommand platformCommand() {
        CapToolDef tool = new CapToolDef("t-a", 1, "t-a", CapToolKind.NATIVE,
                CapabilityRiskLevel.READ, null, null, "{}", "{}", "fp-t-a", 1, CLOCK.instant(), null);
        CapSkillDef skill = new CapSkillDef("s-a", 1, "s-a", null, List.of("t-a"), List.of(),
                CapabilityRiskLevel.READ, "{}", List.of(), "fp-s-a", 1, CLOCK.instant(), null);
        return new ConfigPublishCommand(ConfigRelease.Scope.PLATFORM, null, null,
                "requester-1", "approver-1", null,
                List.of(tool), List.of(skill), List.of(), List.of(), null);
    }

    private static ConfigPublishCommand tenantCommand() {
        return new ConfigPublishCommand(ConfigRelease.Scope.TENANT,
                "11111111-1111-1111-1111-111111111111", null,
                "requester-1", "approver-1", null,
                List.of(), List.of(), List.of(), List.of(), null);
    }

    // ⚠️ 以下端口在「作用域闸门」路径上<b>永远不会被调用</b> ——
    //    闸门在任何凭据使用之前就抛出。它们只是让构造成立。
    //
    // ⚠️ 指针与发布头端口需要返回**真实对象**：回滚协议在开事务<b>之前</b>
    //    先读「回滚目标发布记录」与「当前指针」（§6.3 第 6 条），
    //    读到不存在的对象会先抛「回滚目标不存在」，事务根本开不起来，
    //    断言就测不到「运维事务是否被触达」这件事。
    private static ConfigPointerPort unusedPointer() {
        return new ConfigPointerPort() {
            @Override
            public Optional<ConfigPointer> current(ConfigRelease.Scope scope, String tenantId) {
                return Optional.of(new ConfigPointer(
                        ConfigPointer.pointerKey(scope, tenantId), scope, tenantId,
                        77L, 77L, CLOCK.instant()));
            }

            @Override
            public boolean compareAndSet(ConfigRelease.Scope scope, String tenantId,
                                         Long expectedPublishSeq, long newReleaseId,
                                         long newPublishSeq) {
                throw new UnsupportedOperationException("闸门路径不应触达指针端口");
            }
        };
    }

    private static ConfigReleasePort unusedReleases() {
        return new ConfigReleasePort() {
            @Override
            public ConfigRelease save(ConfigRelease release) {
                throw new UnsupportedOperationException("闸门路径不应触达发布头端口");
            }

            @Override
            public Optional<ConfigRelease> findByReleaseId(String tenantId, long releaseId) {
                return Optional.of(ConfigRelease.pending(ConfigRelease.Scope.PLATFORM, null,
                        "[{\"type\":\"tool\",\"id\":\"t-a\",\"version\":1}]",
                        "fp-agg", ConfigRelease.Status.PUBLISHED, "r", "a",
                        CLOCK.instant(), CLOCK.instant()));
            }

            @Override
            public boolean markRolledBack(String tenantId, long releaseId) {
                throw new UnsupportedOperationException("闸门路径不应触达发布头端口");
            }

            @Override
            public Optional<ConfigRelease> findByPublishSeq(String tenantId, long configPublishSeq) {
                throw new UnsupportedOperationException("闸门路径不应触达发布头端口");
            }

            @Override
            public Optional<ConfigRelease> findLatestPublished(ConfigRelease.Scope scope,
                                                               String tenantId) {
                throw new UnsupportedOperationException("闸门路径不应触达发布头端口");
            }
        };
    }

    private static CapToolDefPort unusedTools() {
        return new CapToolDefPort() {
            @Override
            public boolean insert(CapToolDef def) {
                throw new UnsupportedOperationException("闸门路径不应触达 Tool 端口");
            }

            @Override
            public Optional<CapToolDef> find(String toolId, int version) {
                throw new UnsupportedOperationException("闸门路径不应触达 Tool 端口");
            }

            @Override
            public Optional<CapToolDef> findLatestPublished(String toolId) {
                throw new UnsupportedOperationException("闸门路径不应触达 Tool 端口");
            }

            @Override
            public Optional<CapToolDef> findByMcpBinding(String mcpServerId, String mcpToolName) {
                throw new UnsupportedOperationException("闸门路径不应触达 Tool 端口");
            }
        };
    }

    private static CapSkillDefPort unusedSkills() {
        return new CapSkillDefPort() {
            @Override
            public boolean insert(CapSkillDef def) {
                throw new UnsupportedOperationException("闸门路径不应触达 Skill 端口");
            }

            @Override
            public Optional<CapSkillDef> find(String skillId, int version) {
                throw new UnsupportedOperationException("闸门路径不应触达 Skill 端口");
            }

            @Override
            public Optional<CapSkillDef> findLatestPublished(String skillId) {
                throw new UnsupportedOperationException("闸门路径不应触达 Skill 端口");
            }
        };
    }

    private static CapMcpServerDefPort unusedServers() {
        return new CapMcpServerDefPort() {
            @Override
            public boolean insert(CapMcpServerDef def) {
                throw new UnsupportedOperationException("闸门路径不应触达 MCP Server 端口");
            }

            @Override
            public Optional<CapMcpServerDef> find(String serverId, int version) {
                throw new UnsupportedOperationException("闸门路径不应触达 MCP Server 端口");
            }

            @Override
            public Optional<CapMcpServerDef> findLatestPublished(String serverId) {
                throw new UnsupportedOperationException("闸门路径不应触达 MCP Server 端口");
            }
        };
    }

    private static CapAgentDefPort unusedAgents() {
        return new CapAgentDefPort() {
            @Override
            public boolean insert(CapAgentDef def) {
                throw new UnsupportedOperationException("闸门路径不应触达 Agent 端口");
            }

            @Override
            public Optional<CapAgentDef> find(String tenantId, String agentId, int version) {
                throw new UnsupportedOperationException("闸门路径不应触达 Agent 端口");
            }

            @Override
            public Optional<CapAgentDef> findLatest(String tenantId, String agentId) {
                throw new UnsupportedOperationException("闸门路径不应触达 Agent 端口");
            }

            @Override
            public Optional<CapAgentDef> findLatestPublished(String tenantId, String agentId) {
                throw new UnsupportedOperationException("闸门路径不应触达 Agent 端口");
            }
        };
    }

    private static DecisionAuditPort unusedAudit() {
        return new DecisionAuditPort() {
            @Override
            public void append(DecisionAuditEntry entry) {
                throw new UnsupportedOperationException("闸门路径不应触达审计端口");
            }

            @Override
            public List<DecisionAuditEntry> findByInvocation(String tenantId, String invocationId) {
                throw new UnsupportedOperationException("闸门路径不应触达审计端口");
            }

            @Override
            public List<DecisionAuditEntry> findByRun(String tenantId, String runId) {
                throw new UnsupportedOperationException("闸门路径不应触达审计端口");
            }

            @Override
            public DecisionAuditCoverage coverage(String tenantId) {
                throw new UnsupportedOperationException("闸门路径不应触达审计端口");
            }
        };
    }
}
