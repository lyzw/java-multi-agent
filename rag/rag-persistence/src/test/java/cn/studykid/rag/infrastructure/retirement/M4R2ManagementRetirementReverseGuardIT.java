package cn.studykid.rag.infrastructure.retirement;

/**
 * M4-R2 管理面退役反向验证守卫（骨架，默认跳过，退役后 {@code -Dm4.r2.retired=true} 激活）。
 *
 * <p>退化面 = rag_dictionary / rag_edition / rag_knowledge_point / rag_knowledge_prerequisite /
 * rag_kp_alias / rag_reviewer_roster（20 策略）。本骨架以 rag_dictionary + rag_kp_alias 为代表播种，
 * 反向验证逻辑对所有 R2 表同构。
 *
 * <p><b>⚠️ 接线上线前 TODO</b>：把 {@link #scopedCountSql()} 的硬编码镜像 SQL 替换为对生产
 * Repository 的真实调用（如 {@code JdbcDictionaryRepository} / {@code JdbcCorpusManagementRepository}
 * 的按租户列表面法），照搬 R1 子类 {@code forwardIsolationViaProductionRepository()} 范式，
 * 才能在生产代码层真正捕获「撤掉修复点会红」。R2 涉及多张异构表，建议每表一个正向断言。
 */
class M4R2ManagementRetirementReverseGuardIT extends AbstractRetirementReverseGuardIT {

    // 独立 UUID 命名空间（f2f2…），避开 R1 的 f1f1… 与 RagShadowLiveIT 的 f0f0…。
    private static final String T = "f2f2f2f2-f2f2-f2f2-f2f2-f1f1f1f1f1f1";
    private static final String U = "f2f2f2f2-f2f2-f2f2-f2f2-f2f2f2f2f2f2";

    @Override
    protected String batchProperty() {
        return "r2";
    }

    @Override
    protected String tenantId() {
        return T;
    }

    @Override
    protected String otherTenantId() {
        return U;
    }

    @Override
    protected String[] cleanupSql() {
        return new String[] {
                "DELETE FROM rag_kp_alias",
                "DELETE FROM rag_dictionary"
                // TODO(上线前): 扩展至 rag_edition / rag_knowledge_point /
                // rag_knowledge_prerequisite / rag_reviewer_roster，每表一行清理。
        };
    }

    @Override
    protected void seedTenants() {
        adminJdbcTemplate.update("INSERT INTO rag_dictionary (dict_id, tenant_id, dict_type, code, label, scope) "
                + "VALUES (?::uuid, ?::uuid, 'SUBJECT', 'MATH', '数学', 'TENANT') "
                + "ON CONFLICT (tenant_id, dict_type, code) DO UPDATE SET scope = EXCLUDED.scope", T, T);
        adminJdbcTemplate.update("INSERT INTO rag_dictionary (dict_id, tenant_id, dict_type, code, label, scope) "
                + "VALUES (?::uuid, ?::uuid, 'SUBJECT', 'MATH', '数学', 'TENANT') "
                + "ON CONFLICT (tenant_id, dict_type, code) DO UPDATE SET scope = EXCLUDED.scope", U, U);

        adminJdbcTemplate.update("INSERT INTO rag_kp_alias (alias_id, tenant_id, kp_id, alias_text, scope) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, '别名-T', 'TENANT') "
                + "ON CONFLICT (alias_id) DO UPDATE SET scope = EXCLUDED.scope", T, T, T);
        adminJdbcTemplate.update("INSERT INTO rag_kp_alias (alias_id, tenant_id, kp_id, alias_text, scope) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, '别名-U', 'TENANT') "
                + "ON CONFLICT (alias_id) DO UPDATE SET scope = EXCLUDED.scope", U, U, U);
    }

    @Override
    protected String scopedCountSql() {
        // 镜像管理面显式谓词（tenant_id = ?::uuid）。上线前替换为生产 Repository 调用。
        return "SELECT count(*) FROM rag_dictionary WHERE tenant_id = ?::uuid";
    }

    @Override
    protected String unscopedCountSql() {
        // 撤掉谓词：模拟生产回归（RLS 已退役）。
        return "SELECT count(*) FROM rag_dictionary";
    }
}
