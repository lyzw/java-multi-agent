package cn.studykid.multiagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.Scope;
import cn.studykid.multiagent.core.tenant.SubjectContext;

/**
 * 决策日志（{@link JdbcAuthzDecisionLog}）—— <b>审计失败不得影响业务</b>。
 *
 * <p>本类的两条不变式都来自真实事故形态：
 * <ol>
 *   <li><b>审计表不可用 → 不能拖垮请求</b>：日志写在 Permit 返回<b>之前</b>，
 *       一旦外抛，「放行」直接变成 5xx。审计抖动会放大成全站故障。</li>
 *   <li><b>tenant_id 不做 UUID 转型</b>：审计流水必须能记下任意形态的主体标识；
 *       转型失败等于「这次判定没有留下痕迹」，恰好发生在最需要痕迹的时候。</li>
 * </ol>
 */
@DisplayName("JdbcAuthzDecisionLog —— 审计故障隔离")
class JdbcAuthzDecisionLogTest {

    private static SubjectContext subject() {
        return new SubjectContext("t-1", "p-1", Set.of("TENANT_USER"), Scope.TENANT, 3L);
    }

    @Test
    @DisplayName("连接获取失败：不外抛（只记日志）")
    void connectionFailure_isSwallowed() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("审计库不可用");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        JdbcAuthzDecisionLog log = new JdbcAuthzDecisionLog(broken);
        assertThatCode(() -> log.record(subject(), Action.RETRIEVE,
                ResourceRef.collection("agent"), true, null))
                .as("审计写入失败绝不能冒泡到业务路径")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("写入成功：8 个参数按序绑定，tenant_id 以字符串直传（不做 UUID 转型）")
    void success_bindsAllColumns() {
        RecordingJdbc jdbc = new RecordingJdbc();
        JdbcAuthzDecisionLog log = new JdbcAuthzDecisionLog(jdbc.dataSource());

        log.record(subject(), Action.RETRIEVE, ResourceRef.collection("agent"), false, "NO_RULE");

        assertThat(jdbc.sql)
                .as("tenant_id 不得转型：非 UUID 形态的主体也要能记录")
                .doesNotContain("::uuid");
        assertThat(jdbc.params).hasSize(8);
        assertThat(jdbc.params.get(1)).isEqualTo("t-1");       // tenant_id
        assertThat(jdbc.params.get(2)).isEqualTo("p-1");       // principal_id
        assertThat(jdbc.params.get(3)).isEqualTo("TENANT");    // scope
        assertThat(jdbc.params.get(4)).isEqualTo("RETRIEVE");  // action
        assertThat(jdbc.params.get(5)).isEqualTo("agent");     // resource_type
        assertThat(jdbc.params.get(6)).isEqualTo(false);       // permitted
        assertThat(jdbc.params.get(7)).isEqualTo("NO_RULE");   // reason_code
        assertThat(jdbc.commits).as("写入后必须 commit —— 否则连接归还即回滚，日志静默丢失").isEqualTo(1);
    }

    @Test
    @DisplayName("写入语句抛异常：同样不外抛")
    void executeFailure_isSwallowed() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failOnExecute = true;
        JdbcAuthzDecisionLog log = new JdbcAuthzDecisionLog(jdbc.dataSource());

        assertThatCode(() -> log.record(subject(), Action.RETRIEVE,
                ResourceRef.collection("agent"), true, null))
                .doesNotThrowAnyException();
    }

    // ── 极简 JDBC 录制替身（动态代理，不引 Mockito） ────────────────────────

    private static final class RecordingJdbc {
        String sql;
        final List<Object> params = new ArrayList<>();
        int commits;
        boolean failOnExecute;

        DataSource dataSource() {
            Connection conn = proxy(Connection.class, (p, method, args) -> switch (method.getName()) {
                case "setAutoCommit", "close" -> null;
                case "commit" -> {
                    commits++;
                    yield null;
                }
                case "prepareStatement" -> {
                    sql = (String) args[0];
                    yield preparedStatement();
                }
                default -> null;
            });
            return proxy(DataSource.class, (p, method, args) -> {
                if ("getConnection".equals(method.getName())) {
                    return conn;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        private PreparedStatement preparedStatement() {
            return proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
                case "setTimestamp", "setString", "setBoolean" -> {
                    params.add(args.length > 1 ? args[1] : null);
                    yield null;
                }
                // ⚠️ execute() 返回 boolean（不是 void）：返回 null 会被代理拆箱成 NPE
                case "execute" -> false;
                case "executeUpdate" -> {
                    if (failOnExecute) {
                        throw new SQLException("写审计表失败");
                    }
                    yield 1;
                }
                case "close" -> null;
                default -> null;
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }
    }
}
