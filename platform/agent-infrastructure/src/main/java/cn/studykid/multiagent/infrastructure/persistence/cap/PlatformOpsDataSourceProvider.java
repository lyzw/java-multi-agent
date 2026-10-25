package cn.studykid.multiagent.infrastructure.persistence.cap;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.persistence.PlatformOpsSessionPort;
import cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException;

/**
 * 平台运维角色（BYPASSRLS）数据源的持有者与会话守卫。
 *
 * <p>⚠️ <b>它解决的是「上线阻塞项」</b>：
 * {@code cap_config_pointer} / {@code cap_config_release} / {@code cap_decision_log}
 * 的写策略<b>只放行本租户的 TENANT 行</b>（V15/V17 注释），
 * PLATFORM 行必须由平台运维角色写入 ——
 * <b>否则任一租户都能单方面替换全平台生效配置</b>，那是直接的越权。
 * 没有这个接入点，PLATFORM 作用域发布在生产<b>不可用</b>。
 *
 * <p>⚠️ <b>三条不可让步的约束</b>：
 * <ol>
 *   <li><b>fail-closed</b>：未接入运维角色时，PLATFORM 发布<b>明确失败</b>，
 *       <b>绝不</b>回落到应用角色。回落后 RLS 会拒写，
 *       报错点落在 RLS 策略上，离「这个环境没配运维凭据」这一真因极远。</li>
 *   <li><b>只在发布链路可用</b>：运维数据源带 {@code BYPASSRLS}，
 *       它的连接不受任何 RLS 约束。装到业务读路径上 = 租户隔离整体失效。
 *       故它只在 {@link #inPlatformScopeSession} 这一段明确时间窗内可交出连接。</li>
 *   <li><b>身份唯一</b>：运维角色的用户名/口令由配置唯一决定，
 *       不接受在调用点临时指定（见 {@link GuardedPlatformOpsDataSource}）。</li>
 * </ol>
 */
public final class PlatformOpsDataSourceProvider implements PlatformOpsSessionGuard,
        PlatformOpsSessionPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlatformOpsDataSourceProvider.class);

    /** 平台运维会话的嵌套深度（ThreadLocal）。 */
    private static final ThreadLocal<Integer> SESSION_DEPTH = new ThreadLocal<>();

    /** 未接入时的配置项路径。 */
    public static final String CONFIG_ENABLED = "agent.platform-ops.datasource.enabled";

    private final boolean enabled;
    private final DataSource delegate;
    private final DataSource guarded;

    /**
     * @param enabled  是否已接入平台运维角色
     * @param delegate 运维角色的实际数据源；{@code enabled=false} 时传 {@code null}
     */
    public PlatformOpsDataSourceProvider(boolean enabled, @Nullable DataSource delegate) {
        if (enabled && delegate == null) {
            throw new IllegalArgumentException(
                    "enabled=true 时必须提供平台运维数据源实现（不得留空）");
        }
        this.enabled = enabled;
        this.delegate = delegate;
        this.guarded = enabled ? new GuardedPlatformOpsDataSource(delegate, this) : null;
        if (!enabled) {
            log.warn("平台运维角色（BYPASSRLS）未接入：PLATFORM 作用域发布不可用。"
                    + "如需启用，请设置 " + CONFIG_ENABLED + "=true 并配置 "
                    + "agent.platform-ops.datasource.url / username / password");
        }
    }

    /**
     * 构造「未接入」状态。
     *
     * <p>⚠️ <b>这不等于「降级放行」</b>：未接入时 PLATFORM 作用域发布会
     * <b>明确失败</b>（见 {@link #inPlatformScopeSession}），
     * 而不是悄悄用应用角色去写、然后被 RLS 拒掉。
     */
    public static PlatformOpsDataSourceProvider disabled() {
        return new PlatformOpsDataSourceProvider(false, null);
    }

    /**
     * @return 是否已接入平台运维角色
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 取「受会话守卫」的运维数据源（第三十四批）。
     *
     * <p>⚠️ <b>它只在会话窗口内交出连接</b>：窗口外取用一律被守卫拒绝
     * （{@link GuardedPlatformOpsDataSource}）。这正是它与「裸运维数据源」的区别 ——
     * 裸数据源一旦被装配到业务读路径上，租户隔离就整体失效。
     *
     * <p>⚠️ <b>用途仅限构造平台通道的持久化端口</b>
     * （申请单仓储 / 发布头 / 当前指针 / 决策日志）。
     * 应用层不应持有它 —— 应用层需要的只是
     * {@link cn.studykid.multiagent.ports.persistence.PlatformOpsSessionPort}
     * 这一个「在会话里跑这段逻辑」的能力。
     *
     * @return 受守卫的运维数据源；未接入运维角色时为 {@code null}
     */
    public @Nullable DataSource guardedDataSource() {
        return guarded;
    }

    /**
     * 取「受会话守卫」的运维数据源；未接入运维角色时返回<b>取不到连接</b>的替身
     * （第三十四批）。
     *
     * <p>⚠️ <b>为什么不给 {@code null}</b>：平台通道的持久化端口要在装配期就构造好
     * （Spring 的 Bean 依赖图不允许「先有端口、后决定连哪个库」），
     * 端口持有 {@code null} 的话，报错点会落在端口内部的空指针上 ——
     * 离「这个环境没配运维凭据」这一真因极远。
     * 替身在**取连接**时抛 {@link PlatformOpsUnavailableException}，
     * 报错点直接指向真因，且<b>绝不</b>回落到应用角色。
     *
     * @return 受守卫的运维数据源（未接入时为「取不到连接」的替身）
     */
    public @NonNull DataSource guardedOrUnavailableDataSource() {
        return guarded != null ? guarded : new UnavailablePlatformOpsDataSource();
    }

    /**
     * {@inheritDoc}
     *
     * <p>⚠️ 本实现把 {@link PlatformOpsAction} 的调用包进
     * {@link #inPlatformScopeSession(PlatformOpsWork)}，因此
     * 「动作体里用运维端口读写」与「动作体本身」处在**同一个会话窗口**内 ——
     * 动作体跨越事务边界时（CAS 失败后的基线重读等）也不会漏出窗口。
     */
    @Override
    public <T> T inPlatformScopeSession(@NonNull PlatformOpsAction<T> action) {
        return inPlatformScopeSession(ops -> action.run());
    }

    /**
     * 在平台运维会话内执行一段逻辑。
     *
     * <p>⚠️ <b>会话是本类的核心防线</b>：
     * 会话期间交出的运维连接<b>不受 RLS 约束</b>，
     * 因此会话只应覆盖「PLATFORM 作用域的发布事务」这一段，
     * <b>不得</b>包住任何业务读路径。
     *
     * @param action 会话内动作，入参是受守卫的运维数据源
     * @param <T>    返回类型
     * @return 动作的返回值
     * @throws PlatformOpsUnavailableException 若平台运维角色未接入（fail-closed）
     */
    public <T> T inPlatformScopeSession(@NonNull PlatformOpsWork<T> action) {
        if (!enabled || guarded == null) {
            throw new PlatformOpsUnavailableException(CONFIG_ENABLED,
                    "平台运维角色（BYPASSRLS）未接入，PLATFORM 作用域发布不可用。"
                            + "平台级发布头（cap_config_release）与当前指针（cap_config_pointer）"
                            + "的写策略只放行本租户的 TENANT 行，必须由平台运维角色写入。"
                            + "请设置 " + CONFIG_ENABLED + "=true，并配置 "
                            + "agent.platform-ops.datasource.url / username / password。"
                            + "（本错误不表示配置非法，而是该环境的运维凭据缺失；"
                            + "系统不会退回到应用角色去写平台级数据。）");
        }
        Integer outer = SESSION_DEPTH.get();
        SESSION_DEPTH.set(outer == null ? 1 : outer + 1);
        try {
            return action.run(guarded);
        } catch (java.sql.SQLException e) {
            // ⚠️ 与 TransactionalConnectionHolder 的处置一致：
            //    受检异常转成运行时异常，调用方无需层层声明。
            throw new IllegalStateException("平台运维会话内操作失败", e);
        } finally {
            if (outer == null) {
                SESSION_DEPTH.remove();
            } else {
                SESSION_DEPTH.set(outer);
            }
        }
    }

    /**
     * 平台运维会话内的动作。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface PlatformOpsWork<T> {
        /**
         * @param platformOpsDataSource 受守卫的运维数据源
         * @return 返回值
         * @throws java.sql.SQLException 会话内的数据库操作失败
         */
        T run(DataSource platformOpsDataSource) throws java.sql.SQLException;
    }

    /**
     * 要求当前线程处于平台运维会话内（守卫实现）。
     *
     * @throws IllegalStateException 若不在会话内
     */
    @Override
    public void requireActiveSession() {
        if (SESSION_DEPTH.get() == null) {
            throw new IllegalStateException(
                    "平台运维数据源（BYPASSRLS）只能在 PLATFORM 作用域发布事务内使用。"
                            + "该数据源的连接不受行级安全策略约束，"
                            + "若被业务读路径取用，租户隔离将整体失效。"
                            + "如需写入平台级配置，请通过配置发布服务发起 "
                            + "PLATFORM 作用域的发布。");
        }
    }

    /**
     * 关闭底层连接池。
     *
     * <p>⚠️ <b>由容器在应用关闭时调用</b>（Spring 对 {@code AutoCloseable}
     * Bean 会自动推断销毁方法）。不关闭的话，运维连接池会一直占着
     * PostgreSQL 的连接额度 —— 与应用角色池叠加后可能触发
     * {@code FATAL: sorry, too many clients already}，
     * 而报错点会落在别的模块的上下文加载上，离真因极远。
     */
    @Override
    public void close() {
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("关闭平台运维数据源失败（不影响已完成的发布）", e);
            }
        }
    }

    /**
     * 「运维角色未接入」时的数据源替身：<b>取连接必然失败</b>。
     *
     * <p>⚠️ <b>它绝不回落应用角色</b>：回落会让平台级发布走到 RLS 上被拒写，
     * 报错点落在 RLS 策略上，离真因极远；而运维角色的凭据缺失是一个
     * <b>部署配置问题</b>，应当被明确报出来。
     */
    private static final class UnavailablePlatformOpsDataSource implements DataSource {

        @Override
        public java.sql.Connection getConnection() {
            throw new PlatformOpsUnavailableException(CONFIG_ENABLED,
                    "平台运维角色（BYPASSRLS）未接入，PLATFORM 作用域发布不可用。"
                            + "平台级发布头（cap_config_release）与当前指针（cap_config_pointer）"
                            + "的写策略只放行本租户的 TENANT 行，必须由平台运维角色写入。"
                            + "请设置 " + CONFIG_ENABLED + "=true，并配置 "
                            + "agent.platform-ops.datasource.url / username / password。"
                            + "（本错误不表示配置非法，而是该环境的运维凭据缺失；"
                            + "系统不会退回到应用角色去写平台级数据。）");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
            // 无连接可用，写日志器无意义 —— 空实现。
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // 无连接可用 —— 空实现。
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            throw new java.sql.SQLException("不可用的平台运维数据源");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
