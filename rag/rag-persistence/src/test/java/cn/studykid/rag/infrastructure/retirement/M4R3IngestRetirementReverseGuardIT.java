package cn.studykid.rag.infrastructure.retirement;

/**
 * M4-R3 摄取/索引/巡检面退役反向验证守卫（骨架，默认跳过，退役后 {@code -Dm4.r3.retired=true} 激活）。
 *
 * <p>退化面 = rag_ingest_job / rag_index_generation / rag_inspection_record / rag_answer_citation（6 策略）。
 * 本骨架以 rag_ingest_job + rag_inspection_record 为代表播种，反向验证逻辑对所有 R3 表同构。
 *
 * <p><b>⚠️ 白名单保留</b>：{@code rag_access_audit} 的 RLS <b>不退役</b>（高敏审计表，见
 * deploy/sql/rag/16_v1_0_4_r3_rls_retirement.sql 注释），故本守卫<b>不得</b>覆盖该表。
 *
 * <p><b>⚠️ 接线上线前 TODO</b>：把 {@link #scopedCountSql()} 的硬编码镜像 SQL 替换为对生产
 * Repository 的真实调用（如 {@code JdbcIngestJobRepository} 的按租户列表面法），照搬 R1 子类
 * {@code forwardIsolationViaProductionRepository()} 范式，才能在生产代码层真正捕获「撤掉修复点会红」。
 * 建议每表一个正向断言，并扩展 cleanupSql 至 rag_index_generation / rag_answer_citation。
 */
class M4R3IngestRetirementReverseGuardIT extends AbstractRetirementReverseGuardIT {

    // 独立 UUID 命名空间（f3f3…），避开 R1/R2 的 f1f1/f2f2 与 RagShadowLiveIT 的 f0f0。
    private static final String T = "f3f3f3f3-f3f3-f3f3-f3f3-f1f1f1f1f1f1";
    private static final String U = "f3f3f3f3-f3f3-f3f3-f3f3-f2f2f2f2f2f2";

    @Override
    protected String batchProperty() {
        return "r3";
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
                "DELETE FROM rag_inspection_record",
                "DELETE FROM rag_ingest_job"
                // TODO(上线前): 扩展至 rag_index_generation / rag_answer_citation，每表一行清理。
                // 注意 rag_access_audit 的 RLS 保留，不在此列。
        };
    }

    @Override
    protected void seedTenants() {
        adminJdbcTemplate.update("INSERT INTO rag_ingest_job (job_id, tenant_id, source_id, status) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, 'PENDING'::rag_ingest_status) "
                + "ON CONFLICT (job_id) DO UPDATE SET status = EXCLUDED.status", T, T, T);
        adminJdbcTemplate.update("INSERT INTO rag_ingest_job (job_id, tenant_id, source_id, status) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, 'PENDING'::rag_ingest_status) "
                + "ON CONFLICT (job_id) DO UPDATE SET status = EXCLUDED.status", U, U, U);

        adminJdbcTemplate.update("INSERT INTO rag_inspection_record "
                + "(record_id, tenant_id, source_id, chunk_id, inspector_id, expected_role, actual_role, verdict, scope) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'EXPLANATION', 'EXPLANATION', 'PASS', 'TENANT') "
                + "ON CONFLICT (record_id) DO UPDATE SET verdict = EXCLUDED.verdict", T, T, T, T, T);
        adminJdbcTemplate.update("INSERT INTO rag_inspection_record "
                + "(record_id, tenant_id, source_id, chunk_id, inspector_id, expected_role, actual_role, verdict, scope) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'EXPLANATION', 'EXPLANATION', 'PASS', 'TENANT') "
                + "ON CONFLICT (record_id) DO UPDATE SET verdict = EXCLUDED.verdict", U, U, U, U, U);
    }

    @Override
    protected String scopedCountSql() {
        // 镜像摄取面显式谓词（tenant_id = ?::uuid）。上线前替换为生产 Repository 调用。
        return "SELECT count(*) FROM rag_ingest_job WHERE tenant_id = ?::uuid";
    }

    @Override
    protected String unscopedCountSql() {
        // 撤掉谓词：模拟生产回归（RLS 已退役）。
        return "SELECT count(*) FROM rag_ingest_job";
    }
}
