package cn.studykid.rag.management.infrastructure;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.management.core.RagManagementContext;

/**
 * 按作用域建立一次事务的会话租户（RAG-D11 第四刀·第一刀）。
 *
 * <p><b>为什么检索之外的两个管理实现要共用这一个入口</b>：
 * 「平台档不建立会话租户」这件事的正确写法只有一种——
 * <b>显式置空</b>，而不是「跳过 {@code set_config}」。
 * 连接是池化的，角色或会话级也可能带默认值
 * （{@code ALTER ROLE rag_app SET app.tenant_id = '...'}）；
 * 跳过设置等于把「上一个持有这条连接的人的租户」当成自己的作用域。
 * 这条纪律在两个实现里各写一遍，早晚会漂移成两种写法，所以收敛成一个方法。
 *
 * <p><b>置空之后会发生什么</b>（这是设计，不是巧合）：
 * {@code rag_current_tenant()} 对空串返回 {@code NULL}
 * （见 {@code deploy/sql/rag/01_rag_ddl.sql} 的函数体），
 * 于是 RLS 读策略
 * {@code tenant_id = ANY (ARRAY[rag_current_tenant(), rag_global_tenant_id()])}
 * 只剩「全局行」一支——平台档看到的正是平台语料。
 *
 * <p><b>⚠️ 不做的事</b>：本类<b>不</b>为平台档设置全局哨兵。
 * 把 {@link RagCorpusScope#PLATFORM} 的哨兵写进 {@code app.tenant_id}，
 * {@code rag_current_tenant()} 会直接抛 SQLSTATE 42501
 * （迁移 V8 §1 的越权封堵），表现为一条难以定位的 500。
 */
final class RagSessionScope {

    private RagSessionScope() {
    }

    /**
     * 在<b>当前事务内</b>建立作用域会话。
     *
     * <p>⚠️ 必须在 {@link org.springframework.transaction.support.TransactionTemplate}
     * 的回调里调用：{@code set_config(..., true)} 只在事务内有效，
     * 脱离事务立即失效。
     *
     * @param jdbcTemplate 执行 {@code set_config} 的模板
     * @param context 作用域上下文
     * @throws IllegalStateException 跨租户盘点档（需平台运维数据源，属第三刀）
     */
    static void apply(@NonNull JdbcTemplate jdbcTemplate, @NonNull RagManagementContext context) {
        if (context.scope() == RagCorpusScope.ALL) {
            // ⚠️ rag_app 角色的 RLS 放行不了别的租户，走到这里也只能给出
            //    「本租户 ∪ 全局」的错数据。宁可抛错，也不给一个口径不对的清单。
            throw new IllegalStateException("跨租户盘点视图尚未开放（需平台运维数据源，属第三刀）");
        }
        String sessionTenant = context.sessionTenantId();
        if (sessionTenant == null) {
            // ⚠️ set_config 是 SELECT（返回一行），必须用 queryForObject；
            //    用 update() 会抛「A result was returned when none was expected」。
            jdbcTemplate.queryForObject(
                    "SELECT set_config('app.tenant_id', '', true)", String.class);
        } else {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, sessionTenant);
        }
    }
}
