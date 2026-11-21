package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.druid.pool.DruidDataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Druid 连接池工厂的<b>装配期约束</b>守卫（2026-11-21 连接池切 Druid）。
 *
 * <p><b>为什么必须有这类守卫</b>：Druid 的三条硬约束是在 {@code init()} 阶段
 * 才校验的，而 {@code init()} 是 {@code getConnection()} 内部惰性触发的 ——
 * 于是「配错了」的表现形式是<b>第一次取连接时</b>抛 SQLException，
 * 而不是启动失败。现场离真因（某个毫秒数配反了）相当远。
 *
 * <p>典型踩坑（本次迁移实测踩到）：把 Hikari 的 {@code maxLifetime=1_000}
 * 机械映射成 Druid 的 {@code maxEvictableIdleTimeMillis=1_000}，
 * 却忘了 Druid 的 {@code minEvictableIdleTimeMillis} 默认是 5 分钟 ——
 * 上限小于下限，{@code init()} 直接抛
 * {@code maxEvictableIdleTimeMillis must be grater than minEvictableIdleTimeMillis}。
 *
 * <p><b>可证伪命题</b>：
 * <ol>
 *   <li>工厂构造出的池，其空闲回收/保活/初始连接三项参数<b>必然</b>满足 Druid 的硬约束；</li>
 *   <li>违反约束的 {@link DruidPoolFactory.Tuning} 在<b>构造期</b>就被拒绝（不是运行期）；</li>
 *   <li>池<b>不</b>调用 {@code init()} —— 建池阶段不许连库，否则凭据错误会让实例起不来。</li>
 * </ol>
 */
class DruidPoolFactoryTest {

    @Test
    @DisplayName("✅ 工厂产出的池必须满足 Druid 的三条 init 期硬约束")
    void producedPoolSatisfiesDruidInitConstraints() {
        DruidDataSource pool = DruidPoolFactory.create(
                "guard-pool",
                "jdbc:postgresql://127.0.0.1:1/none",
                "app_agent", "pw", "org.postgresql.Driver",
                DruidPoolFactory.Tuning.of(10, 1));

        // 约束①：initialSize <= maxActive
        assertThat(pool.getInitialSize()).isLessThanOrEqualTo(pool.getMaxActive());
        // 约束②：maxEvictableIdleTimeMillis >= minEvictableIdleTimeMillis
        assertThat(pool.getMaxEvictableIdleTimeMillis())
                .as("空闲回收上限不得小于下限，否则 Druid init() 直接失败")
                .isGreaterThanOrEqualTo(pool.getMinEvictableIdleTimeMillis());
        // 约束③：keepAlive 开启时 keepAliveBetweenTimeMillis > timeBetweenEvictionRunsMillis
        assertThat(pool.isKeepAlive()).as("必须开启 keepAlive —— 它是维持 minIdle 的前提")
                .isTrue();
        assertThat(pool.getKeepAliveBetweenTimeMillis())
                .as("保活间隔必须严格大于回收扫描间隔，否则 Druid init() 直接失败")
                .isGreaterThan(pool.getTimeBetweenEvictionRunsMillis());
    }

    @Test
    @DisplayName("✅ 池上限与常驻数越界必须在构造期被拒绝，而不是等到取连接时")
    void invalidTuningIsRejectedAtConstructionTime() {
        assertThatThrownBy(() -> new DruidPoolFactory.Tuning(0, 1, 120_000L, 300_000L, 540_000L))
                .as("池上限为 0 是非法值")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DruidPoolFactory.Tuning(10, -1, 120_000L, 300_000L, 540_000L))
                .as("常驻连接数不能为负")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DruidPoolFactory.Tuning(4, 10, 120_000L, 300_000L, 540_000L))
                .as("常驻连接数不得大于池上限")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DruidPoolFactory.Tuning(10, 1, 30_000L, 300_000L, 540_000L))
                .as("保活间隔必须大于回收扫描间隔（60s）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DruidPoolFactory.Tuning(10, 1, 120_000L, 540_000L, 300_000L))
                .as("空闲回收上限不得小于下限")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("✅ 取连接等待上限必须显式收紧（Druid 的 maxWait 默认 -1 = 无限等待）")
    void maxWaitMustNotBeInfinite() {
        DruidDataSource pool = DruidPoolFactory.create(
                "guard-pool",
                "jdbc:postgresql://127.0.0.1:1/none",
                "app_agent", "pw", "org.postgresql.Driver",
                DruidPoolFactory.Tuning.of(10, 1));

        assertThat(pool.getMaxWait())
                .as("⚠️ Druid 的 maxWait 默认 -1（无限等待）：池耗尽时业务线程会永久挂住")
                .isPositive()
                .isEqualTo(DruidPoolFactory.CONNECT_WAIT_TIMEOUT_MS);
        assertThat(pool.getConnectTimeout()).isPositive();
    }

    @Test
    @DisplayName("✅ 建池阶段不预建连接（凭据错误不得让实例起不来）")
    void poolDoesNotPreCreateConnections() {
        DruidDataSource pool = DruidPoolFactory.create(
                "guard-pool",
                "jdbc:postgresql://127.0.0.1:1/none",
                "app_agent", "pw", "org.postgresql.Driver",
                DruidPoolFactory.Tuning.of(10, 1));

        assertThat(pool.getInitialSize())
                .as("initialSize 必须为 0：非 0 会让 init() 预建连接，凭据错误直接让启动失败")
                .isZero();
    }

    @Test
    @DisplayName("✅ connection-init-sql 走 Druid 的集合入参")
    void connectionInitSqlIsAppliedAsCollection() {
        DruidDataSource pool = DruidPoolFactory.create(
                "guard-pool",
                "jdbc:postgresql://127.0.0.1:1/none",
                "app_agent", "pw", "org.postgresql.Driver",
                DruidPoolFactory.Tuning.of(10, 1));

        DruidPoolFactory.applyConnectionInitSql(pool, "SET search_path TO public");
        assertThat(pool.getConnectionInitSqls())
                .as("search_path 是 schema 钉死的关键，漏了就会把表建到错误的域")
                .containsExactly("SET search_path TO public");

        // 空白视为未配置：与各配置类的 trimmed() 口径一致
        DruidDataSource blank = DruidPoolFactory.create(
                "guard-pool-2",
                "jdbc:postgresql://127.0.0.1:1/none",
                "app_agent", "pw", "org.postgresql.Driver",
                DruidPoolFactory.Tuning.of(10, 1));
        DruidPoolFactory.applyConnectionInitSql(blank, "   ");
        assertThat(blank.getConnectionInitSqls()).isEmpty();
    }
}
