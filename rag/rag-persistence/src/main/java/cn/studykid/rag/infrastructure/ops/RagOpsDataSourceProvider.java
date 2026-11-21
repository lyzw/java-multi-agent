package cn.studykid.rag.infrastructure.ops;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.alibaba.druid.pool.DruidDataSource;

/**
 * {@link RagOpsSessionPort} 的实现：独立 Druid 池 + 线程绑定的会话窗。
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

    /**
     * 保活探测间隔（毫秒）。
     *
     * <p><b>⚠️ 为什么必须保活</b>：平台全局摄取是「解析十几分钟 → 末尾一次性写 embedding/chunk」的长事务。
     * 池里预先建好的连接在 Docling 长册解析期间长时间空闲，而托管 RDS（阿里云等）会回收
     * 空闲超过数百秒的连接；Druid 默认 {@code keepAlive=false}（不保活），
     * 于是写阶段拿到一条已被服务端关闭的死连接，报
     * {@code This connection has been closed.}（2026-11-10 语文下册 146 页摄取的实测根因：
     * rag-platform-ops 池告警 Failed to validate connection）。
     * 保活间隔取 2 分钟，远小于常见 300s 空闲回收线，让 Druid 在连接被服务端回收前先 ping 一次。
     */
    static final long OP_POOL_KEEPALIVE_MS = 120_000L;

    /**
     * 空闲连接回收（毫秒，5 分钟）。
     *
     * <p>必须 <b>大于</b> {@link #OP_POOL_KEEPALIVE_MS}、<b>小于</b>
     * {@link #OP_POOL_MAX_LIFETIME_MS}：Druid 在 {@code init()} 阶段会校验
     * {@code maxEvictableIdleTimeMillis >= minEvictableIdleTimeMillis}，
     * 配错即建池失败。
     */
    static final long OP_POOL_IDLE_TIMEOUT_MS = 300_000L;

    /**
     * 连接最大存活（毫秒，9 分钟）。
     *
     * <p>对应 Druid 的 {@code maxEvictableIdleTimeMillis}（空闲回收上限），
     * 小于托管库常见的连接回收上限；配合保活实现「空闲连接定期轮换、绝不拿死连接」。
     */
    static final long OP_POOL_MAX_LIFETIME_MS = 540_000L;

    /** 校验单条连接是否可用的超时（毫秒）。 */
    static final long OP_POOL_VALIDATION_TIMEOUT_MS = 5_000L;

    /**
     * 空闲连接回收的扫描间隔（毫秒）。
     *
     * <p>⚠️ 必须 <b>小于</b> {@link #OP_POOL_KEEPALIVE_MS}：Druid 在 {@code init()} 阶段会校验
     * 「{@code keepAliveBetweenTimeMillis > timeBetweenEvictionRunsMillis}」，
     * 配反了就是建池直接失败。
     */
    static final long OP_POOL_EVICTION_RUNS_MS = 60_000L;

    static {
        // 装配期常量自检：把「keepalive < idle < maxLifetime」与 Druid 的两条硬约束
        // 钉在代码里，配错即启动失败。
        if (OP_POOL_KEEPALIVE_MS <= 0
                || OP_POOL_IDLE_TIMEOUT_MS <= OP_POOL_KEEPALIVE_MS
                || OP_POOL_MAX_LIFETIME_MS <= OP_POOL_IDLE_TIMEOUT_MS
                || OP_POOL_VALIDATION_TIMEOUT_MS <= 0
                || OP_POOL_KEEPALIVE_MS <= OP_POOL_EVICTION_RUNS_MS) {
            throw new IllegalStateException(
                    "运维池保活参数非法：keepalive=" + OP_POOL_KEEPALIVE_MS
                            + " / idleTimeout=" + OP_POOL_IDLE_TIMEOUT_MS
                            + " / maxLifetime=" + OP_POOL_MAX_LIFETIME_MS
                            + " / validationTimeout=" + OP_POOL_VALIDATION_TIMEOUT_MS
                            + " / evictionRuns=" + OP_POOL_EVICTION_RUNS_MS);
        }
    }

    private final boolean enabled;
    private final String missingConfiguration;
    private final DruidDataSource pool;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean roleAsserted = new AtomicBoolean(false);

    private RagOpsDataSourceProvider(boolean enabled, String missingConfiguration,
                                     DruidDataSource pool) {
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
     * <p>⚠️ <b>刻意不调用 {@code init()}</b>：Druid 的 {@code getConnection()} 会惰性初始化，
     * 因此建池阶段<b>不</b>尝试连接。口令错、库不可达应当表现为「这一次治理动作失败」，
     * 而不是让一个只跑检索的实例起不来。
     *
     * @param url        JDBC URL
     * @param username   运维角色名（应与 {@code rag_app} 不同）
     * @param password   运维角色口令
     * @param maxPoolSize 池上限（治理动作低频，2 足够；池越大意味着越多条绕过 RLS 的连接）
     */
    public static RagOpsDataSourceProvider connected(String url, String username, String password,
                                                     int maxPoolSize) {
        DruidDataSource pool = new DruidDataSource();
        // ⚠️ 池名必须**每实例唯一**：Druid 用池名做 JMX ObjectName，
        //    同名池的第二个实例注册失败，关闭时注销抛 InstanceNotFoundException，
        //    Druid 把它记成 **ERROR** 级别（污染关停日志）。
        pool.setName("rag-platform-ops-" + System.identityHashCode(pool));
        pool.setUrl(url);
        // ⚠️ 凭据分开设置，不拼进 URL：拼进 URL 在本仓的 IT 里曾表现为认证方式被改写。
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setDriverClassName("org.postgresql.Driver");
        pool.setMaxActive(Math.max(1, maxPoolSize));
        pool.setMinIdle(1);
        // ⚠️ 显式 0：建池阶段不预建连接（凭据错误不应让实例起不来）
        pool.setInitialSize(0);
        pool.setMaxWait(OP_POOL_VALIDATION_TIMEOUT_MS);
        pool.setConnectTimeout((int) OP_POOL_VALIDATION_TIMEOUT_MS);
        pool.setConnectionInitSqls(List.of(SEARCH_PATH_SQL));
        // 空闲保活 + 定期轮换：避免长册摄取期间的连接被托管库回收成死连接。
        // ⚠️ keepAlive 同时是「维持 minIdle」的前提：Druid 的后台补连接线程
        //    只在 keepAlive 打开时才会把池补到 minIdle。
        pool.setKeepAlive(true);
        pool.setKeepAliveBetweenTimeMillis(OP_POOL_KEEPALIVE_MS);
        pool.setMinEvictableIdleTimeMillis(OP_POOL_IDLE_TIMEOUT_MS);
        pool.setMaxEvictableIdleTimeMillis(OP_POOL_MAX_LIFETIME_MS);
        pool.setValidationQueryTimeout((int) OP_POOL_VALIDATION_TIMEOUT_MS);
        pool.setTimeBetweenEvictionRunsMillis(OP_POOL_EVICTION_RUNS_MS);
        return new RagOpsDataSourceProvider(true, null, pool);
    }

    /**
     * 底层 Druid 池（包内可见，供单测断言保活参数确实装配到位）。
     *
     * <p>⚠️ 不对外暴露真实连接池：外部调用方只能拿 {@link RagOpsSessionPort} 的会话窗；
     * 这里只在同包测试里读取配置，防止「加了保活代码却没生效」的假绿。
     */
    DruidDataSource pool() {
        return pool;
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
