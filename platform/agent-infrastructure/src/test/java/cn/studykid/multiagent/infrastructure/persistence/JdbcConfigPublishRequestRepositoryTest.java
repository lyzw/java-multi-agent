package cn.studykid.multiagent.infrastructure.persistence;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.ConfigPublishRequest;
import cn.studykid.multiagent.ports.persistence.AuditPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置变更申请的<b>审计原子性</b>单测（P1-15）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：P1-15 是一个
 * <b>「变更已生效、审计却没写、调用方还收到异常」</b>的缺陷。
 *
 * <p>修复前的顺序是：
 * <pre>
 *   UPDATE cap_config_publish_request ... → conn.commit() → audit.recordRequired(...)
 * </pre>
 * 审计写失败（fail-closed，抛异常）时，状态变更<b>已经提交</b> ——
 * 于是得到「无审计的高危变更」，而类注释写明的恰恰是
 * 「四步均审计 fail-closed：任一审计写失败即中止 —— 高危变更无痕比变更失败危险得多」。
 *
 * <p>现改为：状态变更与审计写入<b>同一事务</b>，
 * 审计写不进去 → 状态变更一并回滚。
 *
 * <p>⚠️ <b>为什么用假 JDBC 而不是真库</b>：本用例要断言的是
 * 「提交与回滚的<b>先后次序</b>」，而那正是真库 IT 看不到的中间态
 * （IT 只能看到最终行状态，看不到「审计失败后到底提交了还是回滚了」）。
 * 假 JDBC 把 {@code commit()} / {@code rollback()} 的调用记录下来，
 * 断言点因此是确定的。
 */
class JdbcConfigPublishRequestRepositoryTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    /** 记录事务边界的假连接。 */
    private static final class TxProbe {
        final AtomicBoolean committed = new AtomicBoolean(false);
        final AtomicBoolean rolledBack = new AtomicBoolean(false);
    }

    private static DataSource fakeDataSource(TxProbe probe) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcConfigPublishRequestRepositoryTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return fakeConnection(probe);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Connection fakeConnection(TxProbe probe) {
        return (Connection) Proxy.newProxyInstance(
                JdbcConfigPublishRequestRepositoryTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setAutoCommit":
                            return null;
                        case "getAutoCommit":
                            return Boolean.FALSE;
                        case "commit":
                            probe.committed.set(true);
                            return null;
                        case "rollback":
                            probe.rolledBack.set(true);
                            return null;
                        case "close":
                            return null;
                        case "isClosed":
                            return Boolean.FALSE;
                        case "prepareStatement":
                            return fakeStatement();
                        case "unwrap":
                            return null;
                        case "isWrapperFor":
                            return Boolean.FALSE;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static PreparedStatement fakeStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                JdbcConfigPublishRequestRepositoryTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeQuery":
                            return fakeResultSet();
                        case "execute":
                            return Boolean.TRUE;
                        case "getUpdateCount":
                            return 1;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static ResultSet fakeResultSet() {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(
                JdbcConfigPublishRequestRepositoryTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return first.getAndSet(false);
                        case "getString":
                            return "req-1";
                        case "getTimestamp":
                            return Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** 审计端口替身：可配置为「写失败」。 */
    private static final class RecordingAudit implements AuditPort {

        private final boolean fail;
        int calls;

        RecordingAudit(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void recordRequired(String tenantId, String actorId, String action,
                                   String resourceType, String resourceId,
                                   Map<String, Object> detail) {
            calls++;
            if (fail) {
                throw new IllegalStateException("审计写入失败（注入）");
            }
        }

        @Override
        public void recordAccess(String tenantId, String actorId, String action,
                                 String resourceType, String resourceId,
                                 Map<String, Object> detail) {
        }

        @Override
        public java.util.List<AuditView> findByResource(String tenantId,
                                                        String resourceType,
                                                        String resourceId) {
            return java.util.List.of();
        }
    }

    @Test
    @DisplayName("⚠️⚠️ 审计写失败 → 状态变更不得提交（P1-15 反证）")
    void auditFailurePreventsCommit() {
        TxProbe probe = new TxProbe();
        RecordingAudit audit = new RecordingAudit(true);
        JdbcConfigPublishRequestRepository repository =
                new JdbcConfigPublishRequestRepository(fakeDataSource(probe), audit);

        assertThatThrownBy(() -> repository.insert(TENANT,
                ConfigPublishRequest.Operation.PUBLISH, "{}", "变更理由", "requester-1"))
                .as("审计写失败必须让主流程失败（fail-closed）")
                .isInstanceOf(IllegalStateException.class);

        assertThat(probe.committed.get())
                .as("⚠️⚠️ 审计写失败时**绝不能**已经提交 —— "
                        + "修复前是「先 commit 状态变更、再写审计」，"
                        + "于是得到「无审计的高危变更」，而调用方还收到异常")
                .isFalse();
        assertThat(probe.rolledBack.get())
                .as("⚠️⚠️ 审计写失败必须回滚（状态变更不得残留）")
                .isTrue();
        assertThat(audit.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 审计写成功 → 状态变更与审计一起提交")
    void auditSuccessCommitsOnce() {
        TxProbe probe = new TxProbe();
        RecordingAudit audit = new RecordingAudit(false);
        JdbcConfigPublishRequestRepository repository =
                new JdbcConfigPublishRequestRepository(fakeDataSource(probe), audit);

        ConfigPublishRequest created = repository.insert(TENANT,
                ConfigPublishRequest.Operation.PUBLISH, "{}", "变更理由", "requester-1");

        assertThat(created.requestId()).isEqualTo("req-1");
        assertThat(created.state()).isEqualTo(ConfigPublishRequest.State.PENDING);
        assertThat(probe.committed.get()).isTrue();
        // ⚠️ 不断言「未回滚」：租约在 close() 时无条件补一次 rollback
        //    （驱动对已提交事务的回滚是空操作），那是租约的既有语义，与本用例无关。
        assertThat(audit.calls).isEqualTo(1);
    }
}
