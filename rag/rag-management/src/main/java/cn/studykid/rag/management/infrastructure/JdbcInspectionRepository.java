package cn.studykid.rag.management.infrastructure;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.management.core.InspectionGuard;
import cn.studykid.rag.management.core.InspectionRecord;
import cn.studykid.rag.management.core.InspectionRules;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.ports.InspectionPort;

/**
 * {@link InspectionPort} 的 JDBC 实现（V1.0.2 S3-1 / 设计 §2.2.3）。
 *
 * <p><b>⚠️ 抽检写入的三条纪律</b>：
 * <ol>
 *   <li><b>整组同事务</b>：结论由记录聚合而来，半组记录会让结论与依据不一致；</li>
 *   <li><b>结论与状态回写同事务</b>：{@code rag_source.inspection_status} 必须与
 *       记录同时可见，否则会出现「记录写着 FAILED、状态是 PASSED」的窗口期，
 *       而 VERIFIED 守卫正是在读这个状态；</li>
 *   <li><b>只追加</b>：不提供 UPDATE/DELETE——抽检记录是证据。</li>
 * </ol>
 */
public class JdbcInspectionRepository implements InspectionPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    /** 平台全局语料的写通道（BYPASSRLS 的 rag_ops）；为 null 时平台档抽检直接 503。 */
    private final RagOpsSessionPort opsSession;

    public JdbcInspectionRepository(@NonNull JdbcTemplate jdbcTemplate,
                                    @NonNull TransactionTemplate transactionTemplate) {
        this(jdbcTemplate, transactionTemplate, null);
    }

    public JdbcInspectionRepository(@NonNull JdbcTemplate jdbcTemplate,
                                    @NonNull TransactionTemplate transactionTemplate,
                                    @org.jspecify.annotations.Nullable RagOpsSessionPort opsSession) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.opsSession = opsSession;
    }

    @Override
    public InspectionRules.Verdict submitInspection(
            @NonNull RagManagementContext context,
            @NonNull String sourceId,
            @NonNull String inspectorId,
            @NonNull List<InspectionRecord> records) {
        // ⚠️ 先在事务外算结论：聚合是纯函数，不需要占用连接。
        InspectionRules.Verdict verdict = InspectionRules.evaluate(records);
        // ⚠️ 与 inspectionStatusOf 同一条纪律：按**查询作用域**取租户。
        //    抽检是 VERIFIED 的前置，若这里只认租户档，平台全局语料就永远抽不了检、
        //    也就永远签不出通过（实测：平台档 requireTenant() → ISE → 500）。
        String tenantId = context.queryTenantId();

        // ⚠️⚠️ 写通道必须按作用域选：平台全局语料的抽检记录是**全局行**
        //    （tenant_id = 全局哨兵、scope = 'PLATFORM'），而 rag_app 的写策略是
        //    `tenant_id = rag_current_tenant() AND scope = 'TENANT'` —— 那条策略
        //    正是「应用角色写不出全局行」的越权闸门，不能用放宽它的方式绕过。
        //    实测：在应用连接上直接 INSERT，得到
        //    `new row violates row-level security policy for table "rag_inspection_record"`（500）。
        //    因此平台档与来源登记/流转一样走 rag_ops 运维会话。
        if (context.scope() == RagCorpusScope.ALL) {
            throw new IllegalStateException("跨租户盘点档不允许任何写动作（只读视图）");
        }
        if (context.scope() == RagCorpusScope.PLATFORM) {
            if (opsSession == null) {
                // 与 corpus 写侧同一语义：通道未接入是「服务端能力缺失」→ 503，
                // 绝不回落到应用角色（回落等于静默写不出全局行）。
                throw new cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException(
                        "rag.ops.datasource",
                        "平台全局语料写通道未接入：抽检记录需要运维数据源（rag_ops），不回落应用角色");
            }
            return opsSession.inGlobalCorpusSession((opsTemplate, opsTx) ->
                    opsTx.execute(status -> writeRecords(
                            opsTemplate, tenantId, sourceId, inspectorId, records, verdict)));
        }
        return transactionTemplate.execute(tx -> {
            // ⚠️ RLS：抽检表与 rag_source 都受租户策略约束，先声明会话。
            RagSessionScope.apply(jdbcTemplate, context);
            return writeRecords(jdbcTemplate, tenantId, sourceId, inspectorId, records, verdict);
        });
    }

    /**
     * 写入抽检记录 + 回写来源抽检状态（调用方负责事务、会话与连接通道）。
     *
     * @param template    本次作用域对应的连接模板
     * @param tenantId    记录所属租户（平台档为全局哨兵）
     * @param sourceId    来源 ID
     * @param inspectorId 抽检人
     * @param records     抽检记录
     * @param verdict     聚合结论
     * @return 判定结果（原样返回，便于调用方直接响应）
     */
    private InspectionRules.Verdict writeRecords(
            @NonNull JdbcTemplate template,
            @NonNull String tenantId,
            @NonNull String sourceId,
            @NonNull String inspectorId,
            @NonNull List<InspectionRecord> records,
            InspectionRules.Verdict verdict) {
        for (InspectionRecord r : records) {
            template.update("""
                            INSERT INTO rag_inspection_record
                                (record_id, tenant_id, source_id, chunk_id, inspector_id,
                                 expected_role, actual_role, error_type, verdict, scope)
                            VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid,
                                    ?, ?, ?, ?, ?)
                            """,
                    r.recordId(), tenantId, sourceId, r.chunkId(), inspectorId,
                    r.expectedRole(), r.actualRole(),
                    r.errorType() == null ? null : r.errorType().name(),
                    r.verdict().name(),
                    // ⚠️ scope 列必须与租户列一致：默认值 'TENANT' 会让全局行
                    //    落在全局 RLS 策略之外（即使走 rag_ops 也会被策略判不匹配）。
                    tenantId.equals(cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID)
                            ? "PLATFORM" : "TENANT");
        }

        // ⚠️ 结论回写来源：VERIFIED 前置守卫读的就是这一列（InspectionGuard）。
        template.update("""
                        UPDATE rag_source SET inspection_status = ?
                         WHERE tenant_id = ?::uuid AND source_id = ?::uuid
                        """,
                InspectionRules.toStatus(verdict).name(), tenantId, sourceId);

        return verdict;
    }

    @Override
    public List<InspectionRecord> listBySource(@NonNull RagManagementContext context,
                                                        @NonNull String sourceId,
                                                        int limit) {
        String tenantId = context.queryTenantId();
        int capped = Math.max(1, Math.min(limit, 500));
        return transactionTemplate.execute(tx -> {
            RagSessionScope.apply(jdbcTemplate, context);
            return jdbcTemplate.query("""
                            SELECT record_id::text, tenant_id::text, source_id::text,
                                   chunk_id::text, inspector_id::text, expected_role,
                                   actual_role, error_type, verdict, created_at
                              FROM rag_inspection_record
                             WHERE tenant_id = ?::uuid AND source_id = ?::uuid
                             ORDER BY created_at DESC
                             LIMIT ?
                            """,
                    (rs, i) -> new InspectionRecord(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7),
                            rs.getString(8) == null
                                    ? null : InspectionRecord.ErrorType.valueOf(rs.getString(8)),
                            InspectionRecord.Verdict.valueOf(rs.getString(9)),
                            rs.getTimestamp(10).toInstant().toString()),
                    tenantId, sourceId, capped);
        });
    }

    @Override
    public String inspectionStatusOf(@NonNull RagManagementContext context,
                                              @NonNull String sourceId) {
        // ⚠️⚠️ 必须按**查询作用域**取租户，不能用 requireTenant()：
        //    签发 VERIFIED 的抽检守卫对平台全局档同样生效，而平台档没有会话租户
        //    （requireTenant() 对非租户档必然抛 ISE）——直接调用会让
        //    「平台全局语料 → 通过校验」一律变成 HTTP 500（实测踩到）。
        //    queryTenantId() 在平台档给出全局哨兵，正是审核目标行所在的租户列。
        String tenantId = context.queryTenantId();
        return transactionTemplate.execute(tx -> {
            // 会话作用域与写侧同源：租户档绑该租户；平台档显式置空
            // （不写哨兵，否则 rag_current_tenant() 抛 SQLSTATE 42501）。见 RagSessionScope。
            RagSessionScope.apply(jdbcTemplate, context);
            return jdbcTemplate.query(
                            "SELECT inspection_status FROM rag_source"
                                    + " WHERE tenant_id = ?::uuid AND source_id = ?::uuid",
                            (rs, i) -> rs.getString(1), tenantId, sourceId)
                    .stream().findFirst()
                    // ⚠️ 默认 NOT_REQUIRED：是新录入来源的初始态。
                    //    InspectionGuard 只认 PASSED，故这个默认值不会误放行。
                    .orElse(InspectionGuard.InspectionStatus.NOT_REQUIRED.name());
        });
    }
}
