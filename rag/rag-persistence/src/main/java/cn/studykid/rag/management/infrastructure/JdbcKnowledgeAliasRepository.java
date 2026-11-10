package cn.studykid.rag.management.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.ports.KnowledgeAliasPort;

/**
 * {@link KnowledgeAliasPort} 的 JDBC 实现（V1.0.2 S3-3）。
 *
 * <p><b>⚠️ 别名文本必须归一化后再存</b>：匹配时 {@code KnowledgePoint.matches}
 * 会先 {@code normalize} 再比对，若入库不归一（全角空格/多余空白），
 * 就会出现「库里写着『二次 函数』、匹配时永远对不上」的静默失效。
 */
public class JdbcKnowledgeAliasRepository implements KnowledgeAliasPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcKnowledgeAliasRepository(JdbcTemplate jdbcTemplate,
                                        TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int addAlias(RagManagementContext context, String kpId, String alias) {
        String tenantId = context.requireTenant().tenantId();
        // ⚠️ 与匹配期同一套归一化：KnowledgePoint.normalize 的口径。
        String normalized = cn.studykid.rag.core.knowledge.KnowledgePoint.normalize(alias);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("别名不能为空");
        }
        return transactionTemplate.execute(tx -> {
            // ⚠️⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、会返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            // ⚠️ ON CONFLICT DO NOTHING + 唯一约束 = 幂等增补。
            //    前端重复提交（双击/重试）不应报错，也不应产生重复行。
            return jdbcTemplate.update("""
                            INSERT INTO rag_kp_alias (alias_id, tenant_id, kp_id, alias_text)
                            VALUES (?::uuid, ?::uuid, ?::uuid, ?)
                            ON CONFLICT (tenant_id, kp_id, alias_text) DO NOTHING
                            """,
                    UUID.randomUUID().toString(), tenantId, kpId, normalized);
        });
    }

    @Override
    public List<String> listAliases(RagManagementContext context, String kpId) {
        String tenantId = context.requireTenant().tenantId();
        return transactionTemplate.execute(tx -> {
            // ⚠️⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、会返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return jdbcTemplate.query(
                    "SELECT alias_text FROM rag_kp_alias"
                            + " WHERE tenant_id = ?::uuid AND kp_id = ?::uuid"
                            + " ORDER BY created_at, alias_text",
                    (rs, i) -> rs.getString(1), tenantId, kpId);
        });
    }

    @Override
    public int removeAlias(RagManagementContext context, String kpId, String alias) {
        String tenantId = context.requireTenant().tenantId();
        String normalized = cn.studykid.rag.core.knowledge.KnowledgePoint.normalize(alias);
        return transactionTemplate.execute(tx -> {
            // ⚠️⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、会返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return jdbcTemplate.update(
                    "DELETE FROM rag_kp_alias"
                            + " WHERE tenant_id = ?::uuid AND kp_id = ?::uuid AND alias_text = ?",
                    tenantId, kpId, normalized);
        });
    }

    @Override
    public List<KnowledgePointBrief> listKnowledgePoints(RagManagementContext context,
                                                         String keyword,
                                                         int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        // ⚠️⚠️ 会话租户必须按作用域建立，绝不能把 queryTenantId() 直接写进 app.tenant_id：
        //    平台档的 queryTenantId() 是全局哨兵，而 rag_current_tenant() 对哨兵会抛
        //    SQLSTATE 42501（迁移 V8 §1 的越权封堵）——实测表现为 GET /knowledge-points
        //    在平台全局档恒 500。RagSessionScope.apply 对平台档显式置空、对租户档绑真实租户；
        //    置空后 RLS 读策略只剩「全局行」一支，正是平台档要看的那批知识点。
        //    这与 listSources / listInspectionSuspects 走的是同一个入口，不另写一份。
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        List<KnowledgePointBrief> rows = transactionTemplate.execute(tx -> {
            RagSessionScope.apply(jdbcTemplate, context);
            return jdbcTemplate.query("""
                    SELECT kp_id::text, subject, grade, canonical_name,
                           COALESCE(array_length(aliases, 1), 0) AS alias_count,
                           COALESCE(array_length(prerequisites, 1), 0) AS prereq_count
                      FROM rag_knowledge_point
                     WHERE tenant_id IN (rag_current_tenant(), rag_global_tenant_id())
                       AND (?::text IS NULL OR canonical_name ILIKE '%' || ?::text || '%')
                     ORDER BY subject, grade, canonical_name
                     LIMIT ?
                    """,
                    (rs, i) -> new KnowledgePointBrief(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getInt(6)),
                    kw, kw, limit);
        });
        return rows == null ? List.of() : rows;
    }
}
