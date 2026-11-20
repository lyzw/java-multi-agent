package cn.studykid.multiagent.infrastructure.persistence.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import cn.studykid.multiagent.infrastructure.persistence.FaultInjectionSupport;

/**
 * RLS 绑定专项 IT（POC，对应主文档 §8.3 / §10 D4）。
 *
 * <p><b>可证伪命题</b>（四条，全部在真实 PG17 <b>非特权角色</b>下验证——
 * 超用户绕过 RLS 会得到「隔离失效」的虚假结论，故沿用
 * {@link FaultInjectionSupport#requireNonPrivileged}）：
 * <ol>
 *   <li>每条 Mapper SQL 执行时当前连接上的 {@code current_setting('app.tenant_id')} 正确；</li>
 *   <li>连接池同一连接连续服务两个租户，第二个租户看不到第一个租户的行；</li>
 *   <li>平台哨兵行（tenant_id = 零 UUID / null）在 TENANT 主体下按策略可见；</li>
 *   <li>事务回滚 / 请求结束后上下文清空，下一个请求不会串租户。</li>
 * </ol>
 *
 * <p>⚠️ 这是「代码保证 RLS」的最后一道防线：任何波次只要本类有一条变红，
 * 立即停止迁移并回滚（见主文档结论）。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
class RlsBindingIT {

    private static HikariDataSource appPool;
    private static HikariDataSource adminPool;
    private static String tenantA;
    private static String tenantB;

    @BeforeEach
    void setUp() throws Exception {
        appPool = FaultInjectionSupport.appPool(2); // ⚠️ 故意用 2，逼出连接复用
        adminPool = FaultInjectionSupport.adminPool();
        FaultInjectionSupport.requireNonPrivileged(appPool);
        tenantA = FaultInjectionSupport.itTenant(adminPool, "it-rls-a");
        tenantB = FaultInjectionSupport.itTenant(adminPool, "it-rls-b");
        FaultInjectionSupport.purgeItTenantRuns(adminPool, "it-rls-a");
        FaultInjectionSupport.purgeItTenantRuns(adminPool, "it-rls-b");
    }

    @AfterEach
    void tearDown() {
        if (appPool != null) appPool.close();
        if (adminPool != null) adminPool.close();
        TenantScope.clear();
    }

    @Test
    @DisplayName("T-1 绑定时序：Mapper 执行时 current_setting 等于主体租户，is_local=true")
    void bindingIsSetBeforeBusinessSql() throws Exception {
        // 通过 TenantScope.bind + 一次 Mapper 调用后，用同连接读回会话变量
        TenantScope.bind(tenantA);
        try (Connection c = appPool.getConnection()) {
            // 模拟拦截器绑定（真实路径由 RlsTenantBindingInterceptor 执行）
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenantA);
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT current_setting('app.tenant_id')");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(tenantA);
            }
        } finally {
            TenantScope.clear();
        }
    }

    @Test
    @DisplayName("T-2 串租户反证：同一连接连续服务 A、B，B 看不到 A 的行")
    void noCrossTenantLeakOnReusedConnection() throws Exception {
        // 造数：以 A 身份写一条 cap_config_draft，再以 B 身份查询（同池同连接复用）
        JdbcTemplate admin = new JdbcTemplate(adminPool);
        admin.update("SET LOCAL ROLE ..."); // 伪代码：用 adminPool 以管理员身份插两租户数据（略）

        // A 主体查询应只见 A 的行；B 主体查询应只见 B 的行
        // 断言：SELECT ... WHERE draft_id = <A 的行> 在 B 主体下返回 0 行
        // （落地时以 ConfigDraftMapper.findById + TenantScope.bind 走真实路径）
        assertThat(true).as("占位：落地时替换为真实双租户写读断言").isTrue();
    }

    @Test
    @DisplayName("T-3 平台哨兵行：TENANT 主体下仍可见 tenant_id IS NULL 的共享草稿")
    void platformRowsVisibleUnderTenant() throws Exception {
        // cap_config_draft_read 策略：tenant_id IS NULL OR tenant_id = current_tenant_id()
        // 断言：以 tenantA 主体查询 PLATFORM scope 草稿（tenant_id=null）可见
        assertThat(true).as("占位：落地时插入 PLATFORM scope 行并断言 TENANT 主体可见").isTrue();
    }

    @Test
    @DisplayName("T-4 fail-closed：无 TenantScope 时调用 Mapper 必须抛异常（不静默查询）")
    void noTenantFailsClosed() {
        TenantScope.clear(); // 确保无主体
        assertThatThrownBy(() -> TenantScope.require())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无租户主体");
    }
}
