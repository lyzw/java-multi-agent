package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 作用域上下文的不变式测试（RAG-D11 第四刀 · 第一刀）。
 *
 * <p><b>⚠️ 本类钉的是「两个值必须分开」这条最容易踩的线</b>：
 * {@code sessionTenantId()}（写进 {@code app.tenant_id} 的会话身份）与
 * {@code queryTenantId()}（绑进读侧 SQL 谓词的查询批次）在租户档同为该租户，
 * 在平台档却一个是 {@code null}、一个是哨兵。
 * 把平台档的哨兵当会话身份，{@code rag_current_tenant()} 直接抛
 * SQLSTATE 42501（迁移 V8 §1 的封堵），表现为一条难以定位的 500——
 * 所以这个差异必须由单测固定住，而不是靠注释提醒。
 *
 * <p><b>⚠️ 第二件事：平台档没有写入口</b>。{@link RagManagementContext#requireTenant()}
 * 在平台档抛异常是<b>刻意的入口缺失</b>；若哪天有人给它加一个「平台档返回哨兵」的分支，
 * 等于把 {@code rag_app} 连接变成写全局语料的通道，RAG-D11 §2.1 的越权闸门当场失效。
 */
class RagManagementContextTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static RagTenantContext tenantCtx() {
        return new RagTenantContext(TENANT, TrustTier.OFFICIAL_TUTORIAL);
    }

    @Test
    @DisplayName("租户档：会话租户与查询批次同为该租户（与本刀之前逐字节一致）")
    void tenantScopeBindsSameValueForBothLayers() {
        RagManagementContext ctx = RagManagementContext.forTenant(tenantCtx());
        assertEquals(RagCorpusScope.TENANT, ctx.scope());
        assertEquals(TENANT, ctx.sessionTenantId());
        assertEquals(TENANT, ctx.queryTenantId());
        assertEquals(TENANT, ctx.requireTenant().tenantId(), "租户档的写入口照常可用");
    }

    @Test
    @DisplayName("⛔ 平台档：查询批次是哨兵，会话租户必须是未设置")
    void platformScopeSeparatesSessionFromQuery() {
        RagManagementContext ctx = RagManagementContext.forPlatform(TrustTier.OFFICIAL_TUTORIAL);
        assertEquals(RagScope.GLOBAL_TENANT_ID, ctx.queryTenantId());
        assertNull(ctx.sessionTenantId(),
                "把哨兵写进 app.tenant_id 会让 rag_current_tenant() 抛 42501");
    }

    @Test
    @DisplayName("⛔ 平台档拿不到「会话租户」：全局写不借道租户身份，连第二刀也不借")
    void platformScopeHasNoTenantSession() {
        RagManagementContext ctx = RagManagementContext.forPlatform(TrustTier.OFFICIAL_TUTORIAL);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, ctx::requireTenant);
        // ⚠️ 第二刀把全局写通道接通了，但 requireTenant() <b>仍然</b>抛：
        //    通道的形状是「另一条连接 + SQL 里的哨兵字面量」，不是「一个租户身份」。
        //    如果这里改成返回哨兵，rag_current_tenant() 会撞 V8 的越权封堵直接 RAISE 42501，
        //    等于把「全局写」伪装成「以平台这个租户的身份写」——两条路必须继续分开。
        assertTrue(ex.getMessage().contains("运维通道"),
                "异常要指向全局写的真实解法，而不是让人以为换个参数就能写：" + ex.getMessage());
    }

    @Test
    @DisplayName("⛔ 档位与租户上下文必须同向：租户档不许空、平台档不许带租户")
    void scopeAndTenantMustAgree() {
        assertThrows(IllegalArgumentException.class,
                () -> new RagManagementContext(RagCorpusScope.TENANT, null, TrustTier.OFFICIAL_TUTORIAL));
        assertThrows(IllegalArgumentException.class,
                () -> new RagManagementContext(RagCorpusScope.PLATFORM, tenantCtx(),
                        TrustTier.OFFICIAL_TUTORIAL));
    }

    @Test
    @DisplayName("盘点档同样不带会话租户：本刀到不了它，命名先留在这")
    void allScopeCarriesNoSessionTenant() {
        RagManagementContext ctx = RagManagementContext.forAll(TrustTier.OFFICIAL_TUTORIAL);
        assertEquals(RagCorpusScope.ALL, ctx.scope());
        assertNull(ctx.sessionTenantId());
        assertEquals(RagScope.GLOBAL_TENANT_ID, ctx.queryTenantId());
        assertThrows(IllegalStateException.class, ctx::requireTenant);
    }

    @Test
    @DisplayName("门槛缺失即拒绝构造：统计口径不允许「不知道门槛」")
    void minTrustTierIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new RagManagementContext(RagCorpusScope.TENANT, tenantCtx(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new RagManagementContext(null, tenantCtx(), TrustTier.OFFICIAL_TUTORIAL));
    }
}
