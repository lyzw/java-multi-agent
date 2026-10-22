package cn.studykid.rag.infrastructure.persistence;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.application.retrieval.RetrievalOrchestrator;
import cn.studykid.rag.core.retrieval.RetrievalRequest;
import cn.studykid.rag.core.retrieval.RetrievalResult;
import cn.studykid.rag.ports.TenantScopedRetrieval;

/**
 * 基于 JDBC 的租户作用域检索实现。
 *
 * <p><b>⚠️ 事务是这里的硬要求</b>：{@code set_config('app.tenant_id', ?, true)}
 * 的第三个参数 {@code is_local=true} 表示「仅在当前事务内有效」，
 * 必须在事务里执行，且必须在任何业务 SQL <b>之前</b>执行。
 * 若脱离事务（自动提交模式），变量立即失效，查询恒返回 0 行，
 * 表现为「检索永远查不到东西」且不报错。
 *
 * <p>因此本类统一用 {@link TransactionTemplate} 包住
 * 「设定租户上下文 → 执行检索」两步，保证二者落在<b>同一个事务、同一条连接</b>上。
 */
public class JdbcTenantScopedRetrieval implements TenantScopedRetrieval {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RetrievalOrchestrator orchestrator;

    public JdbcTenantScopedRetrieval(@NonNull JdbcTemplate jdbcTemplate,
                                     @NonNull TransactionTemplate transactionTemplate,
                                     @NonNull RetrievalOrchestrator orchestrator) {
        if (jdbcTemplate == null || transactionTemplate == null || orchestrator == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.orchestrator = orchestrator;
    }

    @Override
    @NonNull
    public RetrievalResult retrieve(@NonNull String tenantId, @NonNull RetrievalRequest request) {
        // ⚠️ set_config 是 SELECT（返回一行），必须用 queryForObject() 而非 update()。
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return orchestrator.retrieve(request);
        });
    }
}
