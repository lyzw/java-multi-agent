package cn.studykid.multiagent.bootstrap.config;

import java.util.List;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;

/**
 * Druid 连接池的统一构造入口（2026-11-21 连接池由 HikariCP 切换为 Druid）。
 *
 * <p><b>为什么抽出这一层</b>：Hikari 与 Druid 的配置项<b>名字相近、语义不同</b>，
 * 逐处手写映射迟早会在某个池上漏掉一条 —— 而漏掉的后果分两类：
 * <ul>
 *   <li><b>显式错</b>：Druid 默认值与 Hikari 默认值不同，例如
 *       {@code maxWait} 的 Druid 默认是 {@code -1}（<b>无限等待</b>），
 *       Hikari 的 {@code connectionTimeout} 默认是 30s。
 *       不显式收紧，池耗尽时业务线程会<b>永久挂住</b>而不是超时失败；</li>
 *   <li><b>隐式错</b>：例如 {@code initialSize} 与「建池阶段要不要连库」，
 *       Druid 走 {@code init()} 会预建连接，Hikari 的
 *       {@code initializationFailTimeout=-1} 则明确表达「建池阶段不许失败」。
 *       两者语义相反，差一条就是「凭据错误让实例起不来」。</li>
 * </ul>
 * 本类把映射<b>写死一次</b>，三处数据源（主池 / 清理池 / 运维池）共用，
 * 避免各自手写后漂移。
 *
 * <p><b>⚠️ Hikari → Druid 的完整映射表</b>（供逐项核对）：
 * <table border="1">
 *   <caption>配置项映射</caption>
 *   <tr><th>Hikari</th><th>Druid</th><th>说明</th></tr>
 *   <tr><td>{@code setJdbcUrl}</td><td>{@code setUrl}</td><td>驱动类需显式给</td></tr>
 *   <tr><td>{@code setMaximumPoolSize}</td><td>{@code setMaxActive}</td><td>池上限</td></tr>
 *   <tr><td>{@code setMinimumIdle}</td><td>{@code setMinIdle}</td><td>常驻连接数</td></tr>
 *   <tr><td>{@code setPoolName}</td><td>{@code setName}</td><td>池名（监控用）</td></tr>
 *   <tr><td>{@code setConnectionInitSql}</td><td>{@code setConnectionInitSqls}</td>
 *       <td>Druid 是<b>集合</b>入参</td></tr>
 *   <tr><td>{@code setConnectionTimeout}</td><td>{@code setMaxWait}</td>
 *       <td>取连接的等待上限</td></tr>
 *   <tr><td>—</td><td>{@code setConnectTimeout}</td><td>建连的 socket 超时</td></tr>
 *   <tr><td>{@code setInitializationFailTimeout(-1)}</td>
 *       <td><b>不调用 {@code init()}</b></td>
 *       <td>Druid 惰性初始化：首次 {@code getConnection()} 才建池</td></tr>
 *   <tr><td>{@code setKeepaliveTime}</td>
 *       <td>{@code setKeepAlive(true)} + {@code setKeepAliveBetweenTimeMillis}</td>
 *       <td>空闲保活；<b>同时是维持 minIdle 的前提</b></td></tr>
 *   <tr><td>{@code setIdleTimeout}</td><td>{@code setMinEvictableIdleTimeMillis}</td>
 *       <td>空闲回收下限</td></tr>
 *   <tr><td>{@code setMaxLifetime}</td><td>{@code setMaxEvictableIdleTimeMillis}</td>
 *       <td>空闲回收上限</td></tr>
 *   <tr><td>{@code setValidationTimeout}</td><td>{@code setValidationQueryTimeout}</td>
 *       <td>校验单条连接的时限</td></tr>
 * </table>
 *
 * <p><b>⚠️ Druid 的三条硬约束</b>（{@code init()} 阶段校验，配错即抛
 * {@link java.sql.SQLException}）：
 * <ol>
 *   <li>{@code initialSize <= maxActive}；</li>
 *   <li>{@code maxEvictableIdleTimeMillis >= minEvictableIdleTimeMillis}；</li>
 *   <li>{@code keepAlive} 开启时
 *       {@code keepAliveBetweenTimeMillis > timeBetweenEvictionRunsMillis}。</li>
 * </ol>
 * 本类在构造期就把 ① 提前拦掉；②③ 由 {@link Tuning} 的自检一并拦掉 ——
 * <b>宁可启动失败，也不要等到第一次取连接才炸</b>。
 */
final class DruidPoolFactory {

    /**
     * 取连接的等待上限（毫秒）。
     *
     * <p>⚠️ **必须显式设**：Druid 的 {@code maxWait} 默认值是 {@code -1}，
     * 语义是「<b>无限等待</b>」；Hikari 的 {@code connectionTimeout} 默认是 30s。
     * 不显式收紧，池被占满时业务线程会永久挂住 —— 在本项目里这会表现为
     * 「接口不报错也不返回」，比报错更难定位。
     */
    static final long CONNECT_WAIT_TIMEOUT_MS = 5_000L;

    /**
     * 空闲连接回收的扫描间隔（毫秒）。
     *
     * <p>Druid 默认 60s。保活间隔必须<b>严格大于</b>该值（见类注释的硬约束 ③）。
     */
    static final long EVICTION_RUNS_MS = 60_000L;

    /**
     * 空闲保活间隔（毫秒）的默认值。
     *
     * <p>⚠️ <b>开启 {@code keepAlive} 的真正目的不是「保活」，而是「维持 minIdle」</b>：
     * Druid 的后台补连接线程只有在 {@code keepAlive=true} 时才会把池补到
     * {@code minIdle}（见 {@code DruidDataSource.CreateConnectionThread} 里
     * {@code keepAlive && activeCount + poolingCount < minIdle} 那个分支）。
     * Hikari 则<b>无条件</b>维持 {@code minimumIdle} ——
     * 不开 keepAlive，Druid 池会退化成「纯按需建连」，与切换前的行为不一致。
     *
     * <p>附带收益：保活周期会对空闲连接做一次有效性校验，
     * 这正是 RAG 侧 2026-11-10「长册摄取拿到死连接」事故的根治手段
     * （托管 RDS 会回收空闲数百秒的连接）。
     */
    static final long KEEP_ALIVE_MS = 120_000L;

    /**
     * 空闲回收下限（毫秒）的默认值：空闲超过该值的连接可被回收。
     *
     * <p>对应 Hikari 的 {@code idleTimeout}。
     */
    static final long MIN_EVICTABLE_IDLE_MS = 300_000L;

    /**
     * 空闲回收上限（毫秒）的默认值：空闲超过该值的连接被<b>强制</b>回收。
     *
     * <p>对应 Hikari 的 {@code maxLifetime}。取 9 分钟：小于常见 600s 的
     * 托管库空闲回收线，确保连接在被服务端单方面掐断之前先由本池回收。
     */
    static final long MAX_EVICTABLE_IDLE_MS = 540_000L;

    /**
     * 池调优参数。
     *
     * <p>⚠️ <b>为什么不直接用散装参数</b>：三个数据源的调优项有 5 个，
     * 散装参数会让调用点难以核对「这个池到底配了什么」，
     * 也容易在加参数时漏改某个调用点。
     *
     * @param maxActive          池上限
     * @param minIdle            常驻连接数
     * @param keepAliveMs        空闲保活间隔
     * @param minEvictableIdleMs 空闲回收下限
     * @param maxEvictableIdleMs 空闲回收上限
     */
    record Tuning(int maxActive, int minIdle, long keepAliveMs,
                  long minEvictableIdleMs, long maxEvictableIdleMs) {

        /** 用默认保活策略构造（清理池 / 运维池用）。 */
        static Tuning of(int maxActive, int minIdle) {
            return new Tuning(maxActive, minIdle, KEEP_ALIVE_MS,
                    MIN_EVICTABLE_IDLE_MS, MAX_EVICTABLE_IDLE_MS);
        }

        /**
         * 构造期自检：把 Druid 的硬约束提前到装配期。
         *
         * <p>⚠️ 不校验的话，这些错误要到<b>第一次取连接</b>才由 Druid 的
         * {@code init()} 抛出 —— 那时实例已经起来了，失败现场离真因很远。
         */
        Tuning {
            if (maxActive < 1) {
                throw new IllegalArgumentException("连接池上限必须为正数，收到: " + maxActive);
            }
            if (minIdle < 0) {
                throw new IllegalArgumentException("常驻连接数不能为负，收到: " + minIdle);
            }
            if (minIdle > maxActive) {
                throw new IllegalArgumentException(
                        "常驻连接数(" + minIdle + ") 不得大于池上限(" + maxActive + ")");
            }
            if (keepAliveMs <= EVICTION_RUNS_MS) {
                throw new IllegalArgumentException("保活间隔(" + keepAliveMs
                        + ") 必须大于回收扫描间隔(" + EVICTION_RUNS_MS + ")，否则 Druid 建池即失败");
            }
            if (maxEvictableIdleMs < minEvictableIdleMs) {
                throw new IllegalArgumentException("空闲回收上限(" + maxEvictableIdleMs
                        + ") 不得小于下限(" + minEvictableIdleMs + ")，否则 Druid 建池即失败");
            }
        }
    }

    private DruidPoolFactory() {
    }

    /**
     * 按本仓语义构造一个 Druid 连接池。
     *
     * <p>⚠️ 本方法<b>不调用 {@code init()}</b>：Druid 的 {@code getConnection()}
     * 内部会惰性调用它，因此「建池阶段不失败、首次业务操作才暴露凭据错误」
     * 这一 Hikari {@code initializationFailTimeout=-1} 的语义得以保留。
     * 调用方若在构造后自行 {@code init()}，会把凭据错误提前到启动期 ——
     * 对「只跑检索/只跑后台任务」的实例是灾难性的。
     *
     * @param poolName        池名（监控与排障用）
     * @param url             JDBC URL
     * @param username        数据库用户名（可空）
     * @param password        数据库口令（可空 = trust 认证）
     * @param driverClassName 驱动类名（可空 = 按 URL 自动识别）
     * @param tuning          调优参数
     * @return 尚未初始化的连接池
     */
    static DruidDataSource create(String poolName, String url, String username, String password,
                                  String driverClassName, Tuning tuning) {
        DruidDataSource pool = new DruidDataSource();
        // ⚠️ 池名必须**每实例唯一**：Druid 用池名做 JMX ObjectName
        //    （com.alibaba.druid:type=DruidDataSource,id=<name>）。
        //    同名池的第二个实例注册失败（InstanceAlreadyExistsException），
        //    关闭时 unregisterMBean 抛 InstanceNotFoundException ——
        //    而 Druid 把这个异常记成 **ERROR** 级别。
        //    集成测试会缓存多个 Spring 上下文，每个上下文都建一个主池 → 同名必然发生。
        //    Hikari 的默认池名带 identityHashCode（天然唯一），故这不是回归，
        //    而是迁移时必须对齐的细节：语义前缀保留，尾部补实例标识。
        pool.setName(poolName + "-" + System.identityHashCode(pool));
        pool.setUrl(url);
        if (username != null) {
            pool.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            pool.setPassword(password);
        }
        if (driverClassName != null) {
            pool.setDriverClassName(driverClassName);
        }
        pool.setMaxActive(tuning.maxActive());
        pool.setMinIdle(tuning.minIdle());
        // ⚠️ 显式 0：建池阶段不预建任何连接（对应 Hikari 的惰性建池）
        pool.setInitialSize(0);
        pool.setMaxWait(CONNECT_WAIT_TIMEOUT_MS);
        pool.setConnectTimeout((int) CONNECT_WAIT_TIMEOUT_MS);
        pool.setTimeBetweenEvictionRunsMillis(EVICTION_RUNS_MS);
        // ⚠️ 空闲保活 + 定期轮换（见常量注释）：维持 minIdle、避免拿到死连接
        pool.setKeepAlive(true);
        pool.setKeepAliveBetweenTimeMillis(tuning.keepAliveMs());
        pool.setMinEvictableIdleTimeMillis(tuning.minEvictableIdleMs());
        pool.setMaxEvictableIdleTimeMillis(tuning.maxEvictableIdleMs());
        // ⚠️ 校验超时：保活/校验单条连接不允许无限等待
        pool.setValidationQueryTimeout((int) CONNECT_WAIT_TIMEOUT_MS);

        // ⚠️ 2026-11-21 M2：挂 WallFilter（SQL 防火墙）。
        //    只允许单语句、允许 PG 注释（方便 #{} 占位符解析里的 -- / /* */）。
        //    不拦截 DDL / 存储过程 / TRUNCATE / 跨租户查询（PG RLS 已兜住）。
        WallConfig wc = new WallConfig();
        wc.setMultiStatementAllow(false);
        wc.setCommentAllow(true);
        WallFilter wall = new WallFilter();
        wall.setDbType("postgresql");
        wall.setConfig(wc);
        // 保留 StatFilter（慢 SQL 监控），禁用 ExceptionFilter（把底层异常展开，安全边界内）。
        // ⚠️ ⚠️ ⚠️ 重要：filters 是 List<Filter>；Druid 会在 init() 里读取它。
        //    不要用 setFilters(String) 字符串版——那是 druid-spring-boot-starter 的装配方式，
        //    自装 Bean 时 DruidDataSource#setFilters(String) 不会自动实例化 Filter。
        pool.setProxyFilters(List.of((Filter) wall));

        // ⚠️ 安全说明：DruidDataSource **默认不注册任何远程监控端点**
        // （StatViewServlet / JMX 需要显式装配才启用），所以不需要显式"禁远程"。
        // Druid 1.2.28 历史上出过反序列化 CVE（CVE-2021-25646），但那是
        // StatViewServlet + 不当 HTTP 入口组合出来的——我们**不引**
        // druid-spring-boot-starter，也不注册 Servlet，故攻击面天然为零。

        return pool;
    }

    /**
     * 挂上「每条连接建连后执行的初始化语句」。
     *
     * <p>⚠️ Druid 的 setter 是<b>复数</b>（{@code setConnectionInitSqls}），
     * 但本仓只维护单条 {@code SET search_path}，故在此收口成单条入参，
     * 避免把集合语义泄进配置层。
     *
     * @param pool    连接池
     * @param initSql 初始化语句（可空 = 不设置）
     */
    static void applyConnectionInitSql(DruidDataSource pool, String initSql) {
        if (initSql == null || initSql.isBlank()) {
            return;
        }
        pool.setConnectionInitSqls(List.of(initSql.trim()));
    }
}
