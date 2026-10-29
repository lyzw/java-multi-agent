package cn.studykid.rag.infrastructure.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * 平台运维通道（第二刀 §2.2）的<b>不连库</b>单测。
 *
 * <p>本类只钉住「装配与拒绝」这两件事：未接入 fail-closed、窗口外拿不到连接、
 * 不接受外部凭据、配置不齐拒绝启动。
 * 角色体检（BYPASSRLS / 非 superuser）与嵌套会话窗必须对真库说话，
 * 归 {@code JdbcCorpusManagementRepositoryIT} 的运维段验证，不在这里造假连接。
 */
class RagOpsChannelTest {

    /** 未接入的通道：能力判定为否，且任何调用都抛「未接入」而不是回落。 */
    @Test
    void disabledChannelFailsClosed() {
        RagOpsDataSourceProvider port = RagOpsDataSourceProvider.disabled("rag.ops.enabled");

        assertFalse(port.isAvailable(), "未接入的通道不得自称可用");
        RagOpsUnavailableException e = assertThrows(RagOpsUnavailableException.class,
                () -> port.inGlobalCorpusSession((tpl, tx) -> "不该执行到这里"));
        assertEquals("rag.ops.enabled", e.missingConfiguration(),
                "异常要直指该补的配置键，不能只有一句话");
    }

    /** 会话窗外取连接必须直接被拒，且<b>根本不碰真池</b>——放行一次就是一次绕过 RLS。 */
    @Test
    void connectionOutsideSessionWindowIsRefused() {
        AtomicInteger delegateCalls = new AtomicInteger();
        RagOpsDataSourceProvider provider = RagOpsDataSourceProvider.connected(
                "jdbc:postgresql://127.0.0.1:1/none", "rag_ops", "", 1);
        DataSource guarded = new GuardedRagOpsDataSource(
                countingDataSource(delegateCalls), provider::requireActiveSession);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                guarded::getConnection);
        assertTrue(e.getMessage().contains("会话窗"),
                "拒绝理由要写明「只能在会话窗内取连接」，否则排查只能靠猜：" + e.getMessage());
        assertEquals(0, delegateCalls.get(), "窗口外连真池都不该被触碰");
    }

    /** 包装层不接受外部凭据：否则「这条连接是不是运维通道」就不再是装配期能定的事。 */
    @Test
    void guardedDataSourceRejectsExternalCredentials() {
        AtomicInteger delegateCalls = new AtomicInteger();
        DataSource guarded = new GuardedRagOpsDataSource(countingDataSource(delegateCalls),
                () -> {
                });

        assertThrows(IllegalStateException.class,
                () -> guarded.getConnection("someone_else", "their_password"));
        assertEquals(0, delegateCalls.get(), "带外部凭据的请求不得转给真池");
    }

    /** 动作对象为空属于调用方 bug，不能伪装成「通道不可用」。 */
    @Test
    void nullActionIsIllegalArgumentNotUnavailable() {
        RagOpsDataSourceProvider provider = RagOpsDataSourceProvider.connected(
                "jdbc:postgresql://127.0.0.1:1/none", "rag_ops", "", 1);

        // enabled=true 时先过 enabled 判定，再校验动作；顺序反了会把编程错误报成 503。
        assertThrows(IllegalArgumentException.class,
                () -> provider.inGlobalCorpusSession(null));
    }

    /** 关闭开关 → 得到一个 fail-closed 的端口，而不是 null 或缺 bean。 */
    @Test
    void disabledFlagYieldsFailClosedPort() {
        RagOpsSessionPort port = new RagOpsConfiguration().ragOpsSessionPort(
                false, "jdbc:postgresql://127.0.0.1:5432/rag", "rag_ops", "", 2);

        assertFalse(port.isAvailable());
        RagOpsUnavailableException e = assertThrows(RagOpsUnavailableException.class,
                () -> port.inGlobalCorpusSession((tpl, tx) -> null));
        assertEquals("rag.ops.enabled", e.missingConfiguration());
    }

    /**
     * ⚠️ 配置开着但数据源不齐<b>必须拒绝启动</b>（用户定案的风险姿态：默认开 + 常驻补偿控制）。
     *
     * <p>若这里改成「返回 fail-closed 端口」，故障形态会变成「一个永远 503 的哑通道」——
     * 看起来像后端坏了，而真实原因是配置漏了，两者在运行期无法区分。
     */
    @Test
    void enabledWithIncompleteConfigRefusesToStart() {
        RagOpsConfiguration configuration = new RagOpsConfiguration();

        IllegalStateException missingUrl = assertThrows(IllegalStateException.class,
                () -> configuration.ragOpsSessionPort(true, "", "rag_ops", "", 2));
        assertTrue(missingUrl.getMessage().contains("rag.ops.datasource.url"),
                missingUrl.getMessage());

        IllegalStateException missingUser = assertThrows(IllegalStateException.class,
                () -> configuration.ragOpsSessionPort(
                        true, "jdbc:postgresql://127.0.0.1:5432/rag", "  ", "", 2));
        assertTrue(missingUser.getMessage().contains("rag.ops.datasource.username"),
                missingUser.getMessage());
    }

    /** 配置齐备时不得在装配期连库（只跑检索的实例不该被运维库的可用性绑架）。 */
    @Test
    void completeConfigBuildsWithoutConnecting() {
        RagOpsSessionPort port = new RagOpsConfiguration().ragOpsSessionPort(
                true, "jdbc:postgresql://127.0.0.1:1/none", "rag_ops", "", 1);

        assertTrue(port.isAvailable(), "配置齐备即可用；连通性留给首次进窗的角色体检判定");
    }

    /** 一个「被碰到就计数」的假池，用于断言窗口外的请求根本没有转达到真池。 */
    private static DataSource countingDataSource(AtomicInteger calls) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                calls.incrementAndGet();
                throw new SQLException("测试夹具：真池不应被窗口外的请求触达");
            }

            @Override
            public Connection getConnection(String username, String password)
                    throws SQLException {
                calls.incrementAndGet();
                throw new SQLException("测试夹具：带外部凭据的请求不应转达到真池");
            }
        };
    }
}
