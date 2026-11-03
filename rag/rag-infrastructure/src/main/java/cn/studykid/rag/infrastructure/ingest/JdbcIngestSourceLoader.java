package cn.studykid.rag.infrastructure.ingest;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.studykid.rag.application.ingest.IngestJobOrchestrator.IngestSourceView;
import cn.studykid.rag.application.ingest.IngestJobOrchestrator.TenantScopedIngestSource;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 真实的来源视图加载器（V1.0.2 S2-3 / 设计 §3.2）。
 *
 * <p>S1 阶段 Worker 里编排器拿的是 {@code Optional.empty()} 占位实现——那是为了
 * 先打通 job 骨架。本类是它的<b>生产替换</b>：把一次摄取所需的全部输入
 * （载体字节 / 版本坐标 / 知识点）从库里与对象存储里真实读出来。
 *
 * <p><b>⚠️ 为什么加载器住在 {@code rag-infrastructure}</b>：
 * 它同时需要 JDBC（读库）与 {@code ObjectStorePort}（读字节），
 * 而 {@code rag-application} 不依赖这两者。编排器只定义
 * {@code TenantScopedIngestSource} 函数式契约，实现由装配方注入——依赖方向不倒置。
 *
 * <p><b>⚠️ 租户会话必须先就绪</b>：{@code rag_source/rag_document/rag_edition/
 * rag_knowledge_point} 全部受 RLS 约束，调用前必须已
 * {@code set_config('app.tenant_id', ?)}，否则读到 0 行。
 * 本类每个查询都显式带 {@code tenant_id} 谓词作为第二道保险。
 */
public class JdbcIngestSourceLoader implements TenantScopedIngestSource {

    private final JdbcTemplate jdbcTemplate;
    private final java.util.function.Function<String, byte[]> objectReader;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * @param jdbcTemplate  JDBC 模板
     * @param objectReader  按 objectRef 读字节（由装配方注入 {@code ObjectStorePort::read}）
     * @param transactionTemplate 事务模板（租户会话必须在事务内，见 {@link #load}）
     */
    public JdbcIngestSourceLoader(JdbcTemplate jdbcTemplate,
                                  java.util.function.Function<String, byte[]> objectReader,
                                  org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectReader = objectReader;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<IngestSourceView> load(@NonNull String tenantId, @NonNull String sourceId) {
        // ⚠️⚠️ 必须**自带租户会话**：RLS 策略是 {@code tenant_id = rag_current_tenant()}，
        //    无租户会话时「本类每个查询都显式带 tenant_id 谓词」也救不了——
        //    谓词再精确，策略过滤器仍会把行全部滤掉（2026-11-03 实测：读 0 行，
        //    编排器随即报「来源不存在或不可见」，而真实原因是没设租户）。
        //    ⚠️ set_config(..., true) 是**事务局部**：autocommit 上它当场失效，
        //    故整个 load（含版本坐标与知识点读取）必须在同一个事务内完成。
        return transactionTemplate.execute(status -> {
            // ⚠️ 必须用 queryForObject 而非 update：set_config 是 SELECT、返回一行，
            //    update() 期望「无结果集」，否则抛 DataIntegrityViolation（实测 500）。
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                    String.class, tenantId);
            return loadWithinTenant(tenantId, sourceId);
        });
    }

    private Optional<IngestSourceView> loadWithinTenant(String tenantId, String sourceId) {
        // ① 取来源下「最新一份待处理/已处理的载体」：job 是按来源建的，
        //    实际要解析的是该来源下最新上传的那个载体。
        List<DocRow> docs = jdbcTemplate.query("""
                        SELECT d.document_id::text, d.title, d.object_ref,
                               d.edition_id::text, d.parse_status
                          FROM rag_document d
                         WHERE d.tenant_id = ?::uuid AND d.source_id = ?::uuid
                           AND d.object_ref IS NOT NULL
                           AND d.ver_status <> 'REVOKED'::rag_verification_status
                         ORDER BY d.created_at DESC, d.document_id
                         LIMIT 1
                        """,
                (rs, i) -> new DocRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                tenantId, sourceId);
        if (docs.isEmpty()) {
            // ⚠️ 没有可用载体是「job 无活可干」，返回空让编排器显式失败，
            //    而不是造一个空视图去解析空字节（那会以「0 片段」静默成功）。
            return Optional.empty();
        }
        DocRow doc = docs.get(0);

        // ② 版本坐标（切分要写入每个片段，引用溯源依赖它）
        EditionCoordinate edition = loadEdition(tenantId, doc.editionId());
        if (edition == null) {
            return Optional.empty();
        }

        // ③ 知识点（本租户 + 本学科年级可见的，用于挂接）
        List<KnowledgePoint> kps = loadKnowledgePoints(tenantId, edition);

        // ④ 原始字节（fail-closed：读不到就抛，编排器会置 FAILED 并留原因）
        byte[] content = objectReader.apply(doc.objectRef());

        return Optional.of(new IngestSourceView(
                doc.documentId(), doc.title(), edition, doc.editionId(),
                Optional.of(content), kps, "rag-ingest-v1"));
    }

    private EditionCoordinate loadEdition(String tenantId, String editionId) {
        List<EditionCoordinate> rows = jdbcTemplate.query("""
                        SELECT subject, grade, term, publisher, revision_code
                          FROM rag_edition
                         WHERE tenant_id = ?::uuid AND edition_id = ?::uuid
                        """,
                (rs, i) -> new EditionCoordinate(
                        // ⚠️ 库里存的是枚举的 displayName（如「数学」），
                        //    必须按 displayName 反查而不是 name()——用 name() 会全部抛「未知学科值」。
                        subjectOf(rs.getString(1)), gradeOf(rs.getString(2)),
                        termOf(rs.getString(3)), publisherOf(rs.getString(4)),
                        rs.getString(5)),
                tenantId, editionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 加载知识点（含别名）。
     *
     * <p>⚠️ 别名来源是 {@code rag_knowledge_point.aliases}（TEXT[]）——
     * V1.0.2 新增的 {@code rag_kp_alias} 表是它的人工维护入口（S3 落地），
     * 本阶段先读既有列，S3 再把两边合并。
     *
     * <p>⚠️ 只取同学科同年级：跨学科知识点挂接会产生错误归因
     * （「二次函数」被挂到历史学科的知识点上），比漏挂更糟。
     */
    private List<KnowledgePoint> loadKnowledgePoints(String tenantId, EditionCoordinate edition) {
        return jdbcTemplate.query("""
                        SELECT kp_id::text, subject, grade, canonical_name,
                               COALESCE(aliases, ARRAY[]::text[]) AS aliases
                          FROM rag_knowledge_point
                         WHERE tenant_id = ?::uuid AND subject = ? AND grade = ?
                        """,
                (rs, i) -> {
                    // ⚠️ TEXT[] 列必须用 getObject(String[].class) 读：
                    //    直接强转 java.sql.Array 会在运行期抛 ClassCastException。
                    String[] raw = rs.getObject("aliases", String[].class);
                    String[] aliases = raw == null ? new String[0] : raw;
                    return new KnowledgePoint(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), List.of(aliases), List.of(), List.of());
                },
                // ⚠️⚠️ 必须传 **displayName()（字符串）**，不能直接把枚举对象当绑定参数：
                //    JDBC 无法为枚举实例推断 SQL 类型，会抛「无法推测实例
                //    EditionCoordinate$Subject 的 SQL 类型」（2026-11-03 实测）。
                //    库里的 subject/grade 存的就是 displayName（见 loadEdition 的反查注释）。
                tenantId, edition.subject().displayName(), edition.grade().displayName());
    }

    private static cn.studykid.rag.core.version.EditionCoordinate.Subject subjectOf(String s) {
        for (cn.studykid.rag.core.version.EditionCoordinate.Subject v
                : cn.studykid.rag.core.version.EditionCoordinate.Subject.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知学科值：" + s);
    }

    private static cn.studykid.rag.core.version.EditionCoordinate.GradeBand gradeOf(String s) {
        for (cn.studykid.rag.core.version.EditionCoordinate.GradeBand v
                : cn.studykid.rag.core.version.EditionCoordinate.GradeBand.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知年级值：" + s);
    }

    private static cn.studykid.rag.core.version.EditionCoordinate.Term termOf(String s) {
        for (cn.studykid.rag.core.version.EditionCoordinate.Term v
                : cn.studykid.rag.core.version.EditionCoordinate.Term.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知册次值：" + s);
    }

    private static cn.studykid.rag.core.version.EditionCoordinate.Publisher publisherOf(String s) {
        for (cn.studykid.rag.core.version.EditionCoordinate.Publisher v
                : cn.studykid.rag.core.version.EditionCoordinate.Publisher.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知出版社值：" + s);
    }

    /** 载体行。 */
    private record DocRow(String documentId, String title, String objectRef,
                          String editionId, String parseStatus) {
    }
}
