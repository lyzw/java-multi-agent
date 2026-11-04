package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagManagementContext;

/**
 * 平台全局档（scope=PLATFORM）的<b>去重预检</b>不得被「会话租户」挡住。
 *
 * <p><b>回归钉（修复前是反证）</b>：控制器 {@code assertNotDuplicate} 曾写成
 * {@code context.requireTenant().tenantId()}，而 {@code requireTenant()} 对平台档
 * <b>必然</b>抛 {@link IllegalStateException}。于是「平台语料来源上传文档」
 * 一律 500（实测踩到：沪教版〈五·四学制〉英语六年级上册 PDF 导入），
 * 而这正是平台语料抽检/维护的主路径。
 *
 * <p>修复口径：预检是<b>读</b>，租户列取 {@link RagManagementContext#queryTenantId()}
 * （平台档为全局哨兵），会话租户由 {@link RagSessionScope} 按档位建立（平台档显式置空）。
 * 本用例不连数据库：只要不再抛作用域异常即视为通过，之后的失败是环境问题
 * （下文的 JDBC 连接必然失败），与作用域无关。
 */
class IngestDedupPrecheckPlatformScopeTest {

    private static IngestDedupPrecheck precheck() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        // 指向一个不存在的库：本用例只关心「是否撞到作用域闸门」。
        ds.setUrl("jdbc:postgresql://127.0.0.1:1/none");
        ds.setUsername("nobody");
        ds.setPassword("nobody");
        ds.setDriverClassName("org.postgresql.Driver");
        return new IngestDedupPrecheck(new JdbcTemplate(ds));
    }

    @Test
    @DisplayName("平台档载体去重预检 → 不再抛作用域异常（requireTenant 缺陷已修复）")
    void platformScopeReachesCarrierQuery() {
        RagManagementContext platform = RagManagementContext.forPlatform(TrustTier.UNVERIFIED);
        assertDoesNotThrow(() -> {
            try {
                precheck().checkCarrier(platform, "a".repeat(64));
            } catch (RuntimeException e) {
                assertNotScopeFailure(e);
            }
        });
    }

    @Test
    @DisplayName("平台档来源去重预检 → 同样不碰 requireTenant()")
    void platformScopeReachesSourceQuery() {
        RagManagementContext platform = RagManagementContext.forPlatform(TrustTier.UNVERIFIED);
        assertDoesNotThrow(() -> {
            try {
                precheck().checkSource(platform, "9787532773423", null);
            } catch (RuntimeException e) {
                assertNotScopeFailure(e);
            }
        });
    }

    @Test
    @DisplayName("平台档的查询租户是全局哨兵，而会话租户保持未设置")
    void platformTenantColumnsStayApart() {
        RagManagementContext platform = RagManagementContext.forPlatform(TrustTier.UNVERIFIED);
        org.junit.jupiter.api.Assertions.assertEquals(
                cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID, platform.queryTenantId(),
                "平台档预检必须绑定哨兵谓词");
        org.junit.jupiter.api.Assertions.assertNull(platform.sessionTenantId(),
                "会话租户必须保持未设置：写哨兵会让 rag_current_tenant() 抛 42501");
    }

    private static void assertNotScopeFailure(RuntimeException e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("不接受租户上下文") || msg.contains("只有租户档携带会话租户")) {
            throw e;
        }
    }

    /** 档位自证：该测试覆盖的正是平台档。 */
    @Test
    @DisplayName("用例档位自证")
    void scopeIsPlatform() {
        org.junit.jupiter.api.Assertions.assertEquals(RagCorpusScope.PLATFORM,
                RagManagementContext.forPlatform(TrustTier.UNVERIFIED).scope());
    }
}
