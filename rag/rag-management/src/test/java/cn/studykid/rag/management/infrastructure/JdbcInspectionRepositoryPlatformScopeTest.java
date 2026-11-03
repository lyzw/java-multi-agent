package cn.studykid.rag.management.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagManagementContext;

/**
 * 平台全局档（scope=PLATFORM）读取抽检状态<b>不得再被</b>「会话租户」挡住。
 *
 * <p><b>回归钉（修复前是反证）</b>：{@code inspectionStatusOf} 曾写成
 * {@code context.requireTenant().tenantId()}，而 {@code requireTenant()} 对平台档
 * <b>必然</b>抛 {@link IllegalStateException}。抽检守卫又是签发 VERIFIED 的前置，
 * 于是「平台全局语料 → 通过校验」一律 500（实测踩到）。
 *
 * <p>现在改用 {@link RagManagementContext#queryTenantId()}（平台档给全局哨兵），
 * 平台档同样能走到 SQL。本用例不连数据库：只要不再抛作用域异常即视为通过，
 * 之后的失败一律是「没有数据源/事务管理器」这类环境问题，与作用域无关。
 */
class JdbcInspectionRepositoryPlatformScopeTest {

    @Test
    @DisplayName("平台全局档查询抽检状态 → 不再抛作用域异常（requireTenant 缺陷已修复）")
    void platformScopeReachesQuery() {
        JdbcInspectionRepository repository = new JdbcInspectionRepository(
                new JdbcTemplate(), new TransactionTemplate());
        RagManagementContext platform = RagManagementContext.forPlatform(TrustTier.UNVERIFIED);

        assertDoesNotThrow(() -> {
            try {
                repository.inspectionStatusOf(platform, "00000000-0000-0000-0000-000000000001");
            } catch (RuntimeException e) {
                String msg = String.valueOf(e.getMessage());
                // ⚠️ 只允许环境类失败（没有事务管理器 / 没有数据源）；
                //    若又出现「作用域不接受租户上下文」，说明缺陷回归了。
                if (msg != null && (msg.contains("不接受租户上下文")
                        || msg.contains("只有租户档携带会话租户"))) {
                    throw e;
                }
            }
        }, "平台档读到的是全局哨兵那一批行，不应再撞 requireTenant()");
    }
}
