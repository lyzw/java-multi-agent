package cn.studykid.rag.infrastructure.persistence;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.application.generation.GroundedGenerationService;
import cn.studykid.rag.core.generation.GenerationRequest;
import cn.studykid.rag.core.generation.GenerationResult;
import cn.studykid.rag.ports.TenantScopedGeneration;

/**
 * 基于 JDBC 的租户作用域生成实现。
 *
 * <p><b>⚠️ 事务是这里的硬要求</b>（与 {@link JdbcTenantScopedRetrieval} 同构）：
 * {@code set_config('app.tenant_id', ?, true)} 的 {@code is_local=true}
 * 表示「仅在当前事务内有效」，必须在事务里、且在任何业务 SQL <b>之前</b>执行。
 * 脱离事务（自动提交）时变量立即失效，{@code rag_current_tenant()} 恒为 NULL，
 * RLS 过滤掉全部行——表现为「依据永远取不到」且不报错。
 *
 * <p>因此本类用 {@link TransactionTemplate} 包住
 * 「设定租户上下文 → 取回依据 → 装配讲解」三步，保证它们落在
 * <b>同一个事务、同一条连接</b>上。
 *
 * <p><b>⚠️ 为什么不让控制器直接调 {@link GroundedGenerationService}</b>：
 * 那要求每个控制器都记得先开事务再 set_config。漏一次不会报错，
 * 只会静默返回空——这正是本端口存在的理由：把「不可跳过」编码进类型。
 */
public class JdbcTenantScopedGeneration implements TenantScopedGeneration {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final GroundedGenerationService generationService;

    public JdbcTenantScopedGeneration(@NonNull JdbcTemplate jdbcTemplate,
                                      @NonNull TransactionTemplate transactionTemplate,
                                      @NonNull GroundedGenerationService generationService) {
        if (jdbcTemplate == null || transactionTemplate == null || generationService == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.generationService = generationService;
    }

    @Override
    @NonNull
    public GenerationResult generate(@NonNull String tenantId, @NonNull GenerationRequest request) {
        // ⚠️ set_config 是 SELECT（返回一行），必须用 queryForObject() 而非 update()。
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return generationService.generate(request);
        });
    }
}
