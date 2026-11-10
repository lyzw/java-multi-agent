package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.core.RagTenantContext;

/**
 * 2026-11-10 E2E 缺陷 D-02 守卫：平台全局档读知识点时，会话租户必须<b>置空</b>。
 *
 * <p>修复前 {@code listKnowledgePoints} 把 {@code context.queryTenantId()} 直接绑进
 * {@code set_config('app.tenant_id', ?, true)}；平台档取到的是全局哨兵，而
 * {@code rag_current_tenant()} 对哨兵抛 SQLSTATE 42501 —— 表现为
 * {@code GET /knowledge-points?X-Rag-Scope=PLATFORM} 恒 500。
 *
 * <p><b>⚠️ 反向验证</b>：把实现改回「queryTenantId() + set_config(?, …)」，
 * 平台档两个断言（必须置空 / 不得把哨兵当绑定参数）会立刻变红。
 * 哨兵值在本测试里写成<b>字面量</b>，不引用被测实现或领域类的常量，
 * 避免「实现改了守卫跟着改、永远绿」。
 */
class JdbcKnowledgeAliasRepositoryTest {

    /** 全局租户哨兵（与 deploy/sql/rag 迁移里 rag_global_tenant_id() 的返回值一致）。 */
    private static final String GLOBAL_SENTINEL = "00000000-0000-0000-0000-000000000000";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    /** 记录每次 SQL 与全部绑定参数的假 JdbcTemplate / 直通事务模板。 */
    private static final class Recorder {
        private final List<String> sqls = new ArrayList<>();
        private final List<Object[]> calls = new ArrayList<>();
        private final JdbcTemplate jdbc;
        private final TransactionTemplate tx;

        Recorder() {
            jdbc = mock(JdbcTemplate.class, inv -> {
                String method = inv.getMethod().getName();
                Object[] args = inv.getArguments();
                if ("queryForObject".equals(method) || "query".equals(method)) {
                    sqls.add(String.valueOf(args[0]));
                    calls.add(args);
                }
                if ("queryForObject".equals(method)) {
                    return "";
                }
                if ("query".equals(method)) {
                    return List.of();
                }
                return null;
            });
            tx = mock(TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(inv -> {
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
            });
        }

        boolean hasSentinelAsBindParam() {
            return calls.stream().flatMap(Arrays::stream)
                    .anyMatch(o -> o instanceof String s && s.equals(GLOBAL_SENTINEL));
        }

        boolean boundValue(String value) {
            return calls.stream().anyMatch(a -> Arrays.asList(a).contains(value));
        }
    }

    private static JdbcKnowledgeAliasRepository repo(Recorder rec) {
        return new JdbcKnowledgeAliasRepository(rec.jdbc, rec.tx);
    }

    @Test
    @DisplayName("平台档：会话租户显式置空，绝不把全局哨兵写进 app.tenant_id")
    void platformScopeClearsSessionTenantInsteadOfBindingGlobalSentinel() {
        Recorder rec = new Recorder();
        RagManagementContext ctx = RagManagementContext.forPlatform(TrustTier.OFFICIAL_TUTORIAL);

        assertNotNull(repo(rec).listKnowledgePoints(ctx, null, 20));

        assertTrue(rec.sqls.stream().anyMatch(s ->
                        s.contains("set_config('app.tenant_id', '', true)")),
                "平台档必须按作用域显式置空会话租户（RagSessionScope.apply 的唯一正确写法）");
        assertFalse(rec.hasSentinelAsBindParam(),
                "全局哨兵当会话租户会被 rag_current_tenant() 以 42501 拒绝 → HTTP 500");
        assertTrue(rec.sqls.stream().anyMatch(s -> s.contains("FROM rag_knowledge_point")
                        && s.contains("rag_global_tenant_id()")),
                "读范围必须仍包含全局知识点，不能把「修好 500」变成静默空列表");
    }

    @Test
    @DisplayName("租户档：仍绑定真实租户，平台档修复不得改变租户隔离口径")
    void tenantScopeStillBindsRealTenant() {
        Recorder rec = new Recorder();
        RagManagementContext ctx = RagManagementContext.forTenant(
                new RagTenantContext(TENANT, TrustTier.OFFICIAL_TUTORIAL));

        assertNotNull(repo(rec).listKnowledgePoints(ctx, "函数", 20));

        assertTrue(rec.sqls.stream().anyMatch(s ->
                s.contains("set_config('app.tenant_id', ?, true)")));
        assertTrue(rec.boundValue(TENANT), "租户档必须把真实租户绑进会话变量");
        assertFalse(rec.hasSentinelAsBindParam());
    }
}
