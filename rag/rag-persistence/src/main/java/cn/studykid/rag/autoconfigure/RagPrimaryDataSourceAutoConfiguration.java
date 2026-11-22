package cn.studykid.rag.autoconfigure;

import java.util.List;

import javax.sql.DataSource;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * RAG 主数据源的自动配置（2026-11-21 连接池由 HikariCP 切换为 Druid）。
 *
 * <p><b>⚠️ 为什么必须是「自动配置」而不是普通 {@code @Configuration}</b>：
 * RAG 有<b>三个</b>入口，它们的组件扫描范围<b>互不相交</b>——
 * <ul>
 *   <li>{@code rag-bootstrap}：{@code cn.studykid.rag.bootstrap / api / application /
 *       infrastructure / management}；</li>
 *   <li>{@code rag-worker}：{@code cn.studykid.rag.worker} + {@code infrastructure.shadow}；</li>
 *   <li>{@code rag-management}：<b>不是独立启动器</b>，其 API 由 rag-bootstrap 承载。</li>
 * </ul>
 * 换言之，<b>不存在一个能被三个入口同时扫描到的包</b>。
 * 若把主数据源写成普通 {@code @Configuration}，worker 会<b>拿不到 DataSource</b>，
 * 表现为 {@code JdbcTemplate} 装配失败 —— 而 worker 只跑摄取/索引，
 * 缺了它就是「任务永远停在 PENDING」这类<b>不报错也不推进</b>的静默故障
 * （与 {@code RagWorkerApplication} 上关于 {@code @EnableScheduling} 的告警同族）。
 * <b>自动配置不受组件扫描范围约束</b>，是这里唯一能覆盖全部入口的机制。
 *
 * <p><b>⚠️ 为什么必须 {@code before = DataSourceAutoConfiguration.class}</b>：
 * Spring Boot 4.1.1 的 {@code org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration}
 * 在池实现不可用时，会退到 {@code DataSourceConfiguration$Generic} 建一个
 * <b>非池化</b>的 {@code SimpleDriverDataSource}。
 * 本仓已从 {@code spring-boot-starter-jdbc} 排除 HikariCP，
 * 若顺序让 Boot 先跑，就会命中这条兜底分支 —— 结果是「连上了，但没有连接池」，
 * 表现为高并发下连接数暴涨与随机的 {@code too many connections}，
 * <b>且没有任何报错指向连接池</b>。
 * 显式声明 {@code before} 后，本类先注册 {@code dataSource}，
 * Boot 的 {@code @ConditionalOnMissingBean(DataSource.class)} 整段跳过。
 *
 * <p><b>⚠️ 本类不引 druid-spring-boot-starter</b>：Druid 官方的
 * {@code druid-spring-boot-4-starter} 1.2.28 是针对 <b>Spring Boot 4.0.0-M3</b>（里程碑版）
 * 编译的，而本仓基线是 4.1.1 稳定版。让连接池实现由第三方 starter 的编译目标决定，
 * 等于把「池行为」交给一个版本错配的传递变量 —— 故改为本仓自有自动配置。
 *
 * <p><b>配置键</b>：{@code spring.datasource.druid.*}（Druid 语义命名，
 * 区别于 Spring Boot 自带的 {@code spring.datasource.hikari.*}）。
 * url / username / password 仍走 Spring Boot 标准键，由环境变量
 * {@code RAG_PG_*} 提供。
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(DruidDataSource.class)
public class RagPrimaryDataSourceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagPrimaryDataSourceAutoConfiguration.class);

    /** 池名（监控与排障用）。 */
    static final String POOL_NAME = "rag-primary";

    /** 取连接的等待上限（毫秒）。 */
    static final long CONNECT_WAIT_TIMEOUT_MS = 5_000L;

    /** 空闲连接回收的扫描间隔（毫秒）。 */
    static final long EVICTION_RUNS_MS = 60_000L;

    /**
     * 空闲保活间隔（毫秒）。
     *
     * <p>⚠️ 与 RAG 运维池同值：托管 RDS 会回收空闲数百秒的连接，
     * 长册摄取（Docling 解析十几分钟后一次性写 embedding/chunk）期间
     * 池内连接长时间空闲，写阶段会拿到已被服务端关闭的死连接
     * （2026-11-10 语文下册 146 页摄取失败即此根因）。
     */
    static final long KEEP_ALIVE_MS = 120_000L;

    /** 空闲回收下限（毫秒）。 */
    static final long MIN_EVICTABLE_IDLE_MS = 300_000L;

    /** 空闲回收上限（毫秒）。 */
    static final long MAX_EVICTABLE_IDLE_MS = 540_000L;

    /**
     * 主数据源。
     *
     * <p>⚠️ Bean 名必须是 {@code dataSource}：大量注入点靠参数名按名匹配。
     *
     * @param environment 环境（配置来源）
     * @return Druid 连接池（尚未初始化）
     */
    @Bean(name = "dataSource")
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource dataSource(Environment environment) {
        String url = trimmed(environment.getProperty("spring.datasource.url"));
        if (url == null) {
            throw new IllegalStateException("RAG 主数据源未配置 spring.datasource.url。"
                    + "拒绝启动：没有主池时 JdbcTemplate 无从装配，"
                    + "摄取/索引链路会静默停在 PENDING（不报错、不推进）。");
        }
        String username = trimmed(environment.getProperty("spring.datasource.username"));
        // ⚠️ 2026-11-21 M3 fail-closed：username 必填（不能默认 rag_app 明文兜底）。
        //    平台侧 PrimaryDataSourceConfiguration 对 url 缺失即拒绝启动；
        //    RAG 与之对齐，避免运维忘了设 RAG_PG_USERNAME 时静默用默认账号。
        if (username == null) {
            throw new IllegalStateException("RAG 主数据源未配置 spring.datasource.username。"
                    + "拒绝启动：连接身份必须由环境变量 RAG_PG_USERNAME 显式注入"
                    + "（对齐 platform PrimaryDataSourceConfiguration 的 fail-closed 约定）。");
        }
        String password = environment.getProperty("spring.datasource.password");
        String driver = trimmed(environment.getProperty("spring.datasource.driver-class-name"));

        int maxActive = environment.getProperty(
                "spring.datasource.druid.max-active", Integer.class, 10);
        // ⚠️ 默认 10 而非 0：Hikari 的 minimumIdle 默认等于 maximumPoolSize，
        //    切换后若让 min-idle 落回 0，池会退化成「纯按需建连」，
        //    连接复用行为与切换前不一致（表现为首批请求的额外建连延迟）。
        int minIdle = environment.getProperty(
                "spring.datasource.druid.min-idle", Integer.class, 10);
        if (maxActive < 1) {
            throw new IllegalStateException("spring.datasource.druid.max-active 必须为正数，收到: " + maxActive);
        }
        if (minIdle < 0 || minIdle > maxActive) {
            throw new IllegalStateException("spring.datasource.druid.min-idle 必须落在 [0, max-active] 内，收到: "
                    + minIdle + "（max-active=" + maxActive + "）");
        }

        DruidDataSource pool = new DruidDataSource();
        // ⚠️ 池名必须**每实例唯一**：Druid 用池名做 JMX ObjectName
        //    （com.alibaba.druid:type=DruidDataSource,id=<name>）。
        //    同名池的第二个实例注册失败，关闭时 unregisterMBean 抛
        //    InstanceNotFoundException，Druid 把它记成 **ERROR** 级别。
        //    RAG 的三个入口互不相交但同处一个 JVM（本地起多个进程时不会，
        //    但同一 JVM 内多次重建上下文就会），故补实例标识保证唯一。
        pool.setName(POOL_NAME + "-" + System.identityHashCode(pool));
        pool.setUrl(url);
        if (username != null) {
            pool.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            pool.setPassword(password);
        }
        if (driver != null) {
            pool.setDriverClassName(driver);
        }
        pool.setMaxActive(maxActive);
        pool.setMinIdle(minIdle);
        // ⚠️ 显式 0：建池阶段不预建连接，凭据错误应表现为「首次取连接失败」，
        //    而不是「应用启动即失败」—— 只跑检索的实例不该被后台任务的凭据拖垮。
        pool.setInitialSize(0);
        pool.setMaxWait(CONNECT_WAIT_TIMEOUT_MS);
        pool.setConnectTimeout((int) CONNECT_WAIT_TIMEOUT_MS);
        pool.setTimeBetweenEvictionRunsMillis(EVICTION_RUNS_MS);

        long keepAliveMs = environment.getProperty(
                "spring.datasource.druid.keep-alive-between-time-millis", Long.class, KEEP_ALIVE_MS);
        long minEvictableIdleMs = environment.getProperty(
                "spring.datasource.druid.min-evictable-idle-time-millis", Long.class, MIN_EVICTABLE_IDLE_MS);
        long maxEvictableIdleMs = environment.getProperty(
                "spring.datasource.druid.max-evictable-idle-time-millis", Long.class, MAX_EVICTABLE_IDLE_MS);

        // ⚠️ 装配期先拦 Druid 的三条 init 期硬约束：违反它们，Druid 会在
        //    init()（首次取连接时）才抛 SQLException —— 现场离真因（某个毫秒数
        //    配反了）相当远，且表现为「实例起来了但一用就炸」。
        //    这里提前到启动期拒绝，失败现场直指配置键。
        if (keepAliveMs <= EVICTION_RUNS_MS) {
            throw new IllegalStateException(
                    "spring.datasource.druid.keep-alive-between-time-millis 必须大于回收扫描间隔("
                            + EVICTION_RUNS_MS + "ms)，收到: " + keepAliveMs
                            + "（Druid 硬约束：keepAlive 开启时该值必须严格大于 timeBetweenEvictionRunsMillis）");
        }
        if (maxEvictableIdleMs < minEvictableIdleMs) {
            throw new IllegalStateException(
                    "spring.datasource.druid.max-evictable-idle-time-millis(" + maxEvictableIdleMs
                            + ") 不得小于 min-evictable-idle-time-millis(" + minEvictableIdleMs
                            + ")（Druid 硬约束，违反则建池即失败）");
        }

        // ⚠️ keepAlive=true 是「维持 minIdle」的前提：Druid 的后台补连接线程
        //    只在 keepAlive 打开时才会把池补到 minIdle。
        pool.setKeepAlive(true);
        pool.setKeepAliveBetweenTimeMillis(keepAliveMs);
        pool.setMinEvictableIdleTimeMillis(minEvictableIdleMs);
        pool.setMaxEvictableIdleTimeMillis(maxEvictableIdleMs);
        pool.setValidationQueryTimeout(environment.getProperty(
                "spring.datasource.druid.validation-query-timeout", Integer.class,
                (int) CONNECT_WAIT_TIMEOUT_MS));

        // ⚠️ 每条连接都要把 search_path 指向 rag：RAG 全部对象位于独立 schema，
        //    而应用 SQL 一律用未限定表名，否则解析不到对象（relation does not exist）。
        //    public 保留在末尾：pgvector 的 vector 类型在 public。
        String initSql = trimmed(environment.getProperty("spring.datasource.druid.connection-init-sql"));
        if (initSql != null) {
            pool.setConnectionInitSqls(List.of(initSql));
        }

        // ⚠️ 2026-11-21 M2：挂 WallFilter（SQL 防火墙）。
        //    只允许单语句、允许 PG 注释。禁止 setFilters(String) 字符串版——
        //    自装 Bean 时不会自动实例化 Filter（会被静默忽略）。
        WallConfig wc = new WallConfig();
        wc.setMultiStatementAllow(false);
        wc.setCommentAllow(true);
        WallFilter wall = new WallFilter();
        wall.setDbType("postgresql");
        wall.setConfig(wc);
        pool.setProxyFilters(List.of((Filter) wall));

        // ⚠️ 安全说明：DruidDataSource **默认不注册任何远程监控端点**
        // （StatViewServlet / JMX 需要显式装配才启用）。Druid 1.2.28 历史上
        // 出过反序列化 CVE（CVE-2021-25646），但那是 StatViewServlet +
        // 不当 HTTP 入口组合出来的——我们**不引** druid-spring-boot-starter，
        // 也不注册 Servlet，攻击面天然为零。

        // ⚠️ 刻意不调用 init()：Druid 的 getConnection() 内部会惰性调用它，
        //    「建池阶段不失败」由此得到保留。
        return pool;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
