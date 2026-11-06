package cn.studykid.rag.infrastructure.ops;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link RagOpsSessionPort} 的实现：独立 Hikari 池 + 线程绑定的会话窗。
 *
 * <p><b>会话窗用 ThreadLocal 深度计数而不是布尔量</b>：嵌套调用（运维事务里再开一次）
 * 若用布尔量，内层 {@code finally} 会把外层还留在窗口里的连接判成「窗口外」。
 * 深度计数只在归零时清除，语义与调用栈一致。
 *
 * <p><b>⚠️ 首次进窗会做角色断言</b>（见 {@link #assertRole()}）：
 * 这条通道写的是全局桶，而它的正确性依赖两件事同时成立——
 * 角色是 BYPASSRLS（否则全局写被 RLS <b>静默滤成 0 行</b>，控制台却报成功）、
 * 角色不是超级用户（否则连 {@code pg_roles} 的判据本身都不可信）。
 * 这两条都属于「配错了不会立刻报错」的性质，所以放在第一次使用时查实，
 * 而不是等运维在库里找不到自己刚登记的那条语料。
 */
public final class RagOpsDataSourceProvider implements RagOpsSessionPort, AutoCloseable {

    /** 会话窗嵌套深度；null = 不在窗内。 */
    private static final ThreadLocal<Integer> SESSION_DEPTH = new ThreadLocal<>();

    /** RAG 全部对象在 schema rag，应用 SQL 用未限定名——运维池必须设同一个 search_path。 */
    private static final String SEARCH_PATH_SQL = "SET search_path TO rag, public";

    private final boolean enabled;
    private final String missingConfiguration;
    private final HikariDataSource pool;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean roleAsserted = new AtomicBoolean(false);

    private RagOpsDataSourceProvider(boolean enabled, String missingConfiguration,
                                     HikariDataSource pool) {
        this.enabled = enabled;
        this.missingConfiguration = missingConfiguration;
        this.pool = pool;
        if (pool == null) {
            this.jdbcTemplate = null;
            this.transactionTemplate = null;
            return;
        }
        DataSource guarded = new GuardedRagOpsDataSource(pool, this::requireActiveSession);
        this.jdbcTemplate = new JdbcTemplate(guarded);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(guarded));
    }

    /**
     * 未接入的通道：所有调用 fail-closed，绝不回落到应用角色。
     *
     * @param missingConfiguration 需要补齐的配置键（放进异常，让告警直指该补什么）
     */
    public static RagOpsDataSourceProvider disabled(String missingConfiguration) {
        return new RagOpsDataSourceProvider(false, missingConfiguration, null);
    }

    /**
     * 接入运维池。
     *
     * <p>⚠️ {@code initializationFailTimeout = -1}：建池时<b>不</b>尝试连接。
     * 口令错、库不可达应当表现为「这一次治理动作失败」，
     * 而不是让一个只跑检索的实例起不来。
     *
     * @param url        JDBC URL
     * @param username   运维角色名（应与 {@code rag_app} 不同）
     * @param password   运维角色口令
     * @param maxPoolSize 池上限（治理动作低频，2 足够；池越大意味着越多条绕过 RLS 的连接）
     */
    public static RagOpsDataSourceProvider connected(String url, String username, String password,
                                                     int maxPoolSize) {
        HikariDataSource cfg = new HikariDataSource();
        cfg.setJdbcUrl(url);
        // ⚠️ 凭据分开设置，不拼进 URL：拼进 URL 在本仓的 IT 里曾表现为认证方式被改写。
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClassName("org.postgresql.Driver");
        cfg.setPoolName("rag-platform-ops");
        cfg.setMaximumPoolSize(Math.max(1, maxPoolSize));
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000L);
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionInitSql(SEARCH_PATH_SQL);
        return new RagOpsDataSourceProvider(true, null, cfg);
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public <T> T inGlobalCorpusSession(RagOpsAction<T> action) {
        if (!enabled) {
            throw new RagOpsUnavailableException(
                    String.valueOf(missingConfiguration),
                    "平台运维通道未接入（fail-closed，不回落应用角色）");
        }
        if (action == null) {
            throw new IllegalArgumentException("运维会话动作不能为空");
        }
        Integer outer = SESSION_DEPTH.get();
        SESSION_DEPTH.set(outer == null ? 1 : outer + 1);
        try {
            assertRole();
            return action.run(jdbcTemplate, transactionTemplate);
        } finally {
            if (outer == null) {
                SESSION_DEPTH.remove();
            } else {
                SESSION_DEPTH.set(outer);
            }
        }
    }

    /**
     * 当前线程是否处在会话窗内（包内可见，供 {@link RagOpsSessionGuard} 使用）。
     */
    void requireActiveSession() {
        if (SESSION_DEPTH.get() == null) {
            throw new IllegalStateException(
                    "平台运维数据源只能在 inGlobalCorpusSession(...) 会话窗内取连接："
                            + "该角色带 BYPASSRLS，窗口外放行等于把租户隔离交给调用方自觉");
        }
    }

    /**
     * 角色体检（每个实例只做一次）。
     *
     * @throws RagOpsUnavailableException 角色不合格或未连上库
     */
    private void assertRole() {
        if (!roleAsserted.compareAndSet(false, true)) {
            return;
        }
        try {
            RoleFacts facts = jdbcTemplate.queryForObject(
                    """
                    SELECT current_user,
                           (SELECT rolsuper FROM pg_roles WHERE rolname = current_user),
                           (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user)
                    """,
                    (rs, i) -> new RoleFacts(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)));
            if (facts.superUser()) {
                roleAsserted.set(false);
                throw new RagOpsUnavailableException("rag.ops.role",
                        "运维角色 " + facts.role() + " 是超级用户：全局治理不需要 superuser，"
                                + "而 superuser 会绕过 CHECK 约束，让「只写全局桶」失去库层证据");
            }
            if (!facts.bypassRls()) {
                roleAsserted.set(false);
                throw new RagOpsUnavailableException("rag.ops.role",
                        "运维角色 " + facts.role() + " 没有 BYPASSRLS：全局行会被 RLS 策略"
                                + "（tenant_id = rag_current_tenant()）静默滤成 0 行，"
                                + "治理动作会「成功」但什么都没写进去");
            }
        } catch (RagOpsUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            roleAsserted.set(false);
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "运维通道首次连接失败：" + e.getMessage());
        }
    }

    /** 角色体检的读数。 */
    private record RoleFacts(String role, boolean superUser, boolean bypassRls) {
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
