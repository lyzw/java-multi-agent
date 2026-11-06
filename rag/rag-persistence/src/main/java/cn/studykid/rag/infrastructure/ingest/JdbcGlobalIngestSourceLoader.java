package cn.studykid.rag.infrastructure.ingest;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.application.ingest.GlobalIngestJobOrchestrator.GlobalIngestSource;
import cn.studykid.rag.application.ingest.IngestJobOrchestrator.IngestSourceView;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;

/**
 * 平台档（全局桶）真实的来源视图加载器（G1 / RAG-D11 §1.3）。
 *
 * <p>镜像 {@link JdbcIngestSourceLoader}，但读取落在<b>运维会话</b>（{@code rag_ops}）上：
 * 所有谓词写死 {@code tenant_id = rag_global_tenant_id()}，<b>不</b>走
 * {@code set_config('app.tenant_id', ...)}——应用角色对全局行是「静默 0 行」，
 * 设租户会话也救不了（且设哨兵会被 {@code rag_current_tenant()} 越权封堵）。
 * 全局行的可见性只能由 BYPASSRLS 的运维通道保证。
 *
 * <p>⚠️ <b>没有租户参数</b>：方法签名上不暴露租户形态（与全局编排器同源护栏）。
 */
public class JdbcGlobalIngestSourceLoader implements GlobalIngestSource {

    private final java.util.function.Function<String, byte[]> objectReader;
    private final RagOpsSessionPort opsSession;

    /**
     * @param objectReader 按 objectRef 读字节（由装配方注入 {@code ObjectStorePort::read}）
     * @param opsSession   运维会话（全局桶读取通道；为 null 时 load 抛 503 类异常）
     */
    public JdbcGlobalIngestSourceLoader(
            java.util.function.Function<String, byte[]> objectReader,
            RagOpsSessionPort opsSession) {
        this.objectReader = objectReader;
        this.opsSession = opsSession;
    }

    @Override
    public Optional<IngestSourceView> load(@NonNull String sourceId) {
        if (opsSession == null) {
            throw new RagOpsUnavailableException("rag.ops.datasource",
                    "平台全局语料读取需要运维数据源（rag_ops），不回落应用角色");
        }
        // ⚠️ 整个 load（含版本坐标与知识点读取）必须在同一运维事务窗内完成，
        //    故包在 inGlobalCorpusSession 的 TransactionTemplate 中。
        return opsSession.inGlobalCorpusSession((opsTemplate, opsTx) ->
                opsTx.execute(status -> loadWithinGlobal(opsTemplate, sourceId)));
    }

    private Optional<IngestSourceView> loadWithinGlobal(
            org.springframework.jdbc.core.JdbcTemplate opsTemplate, String sourceId) {
        // ① 取全局来源下「最新一份待处理/已处理的载体」（与租户侧同源逻辑）。
        List<DocRow> docs = opsTemplate.query("""
                        SELECT d.document_id::text, d.title, d.object_ref,
                               d.edition_id::text, d.parse_status
                          FROM rag_document d
                         WHERE d.tenant_id = rag_global_tenant_id() AND d.source_id = ?::uuid
                           AND d.object_ref IS NOT NULL
                           AND d.ver_status <> 'REVOKED'::rag_verification_status
                         ORDER BY d.created_at DESC, d.document_id
                         LIMIT 1
                        """,
                (rs, i) -> new DocRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                sourceId);
        if (docs.isEmpty()) {
            return Optional.empty();
        }
        DocRow doc = docs.get(0);

        // ② 版本坐标（切分要写入每个片段，引用溯源依赖它）
        EditionCoordinate edition = loadEdition(opsTemplate, doc.editionId());
        if (edition == null) {
            return Optional.empty();
        }

        // ③ 知识点（全局桶的学科年级可见的，用于挂接）
        List<KnowledgePoint> kps = loadKnowledgePoints(opsTemplate, edition);

        // ④ 原始字节（fail-closed：读不到就抛，编排器会置 FAILED 并留原因）
        byte[] content = objectReader.apply(doc.objectRef());

        return Optional.of(new IngestSourceView(
                doc.documentId(), doc.title(), edition, doc.editionId(),
                Optional.of(content), kps, "rag-ingest-v1"));
    }

    private EditionCoordinate loadEdition(
            org.springframework.jdbc.core.JdbcTemplate opsTemplate, String editionId) {
        List<EditionCoordinate> rows = opsTemplate.query("""
                        SELECT subject, grade, term, publisher, revision_code
                          FROM rag_edition
                         WHERE tenant_id = rag_global_tenant_id() AND edition_id = ?::uuid
                        """,
                (rs, i) -> new EditionCoordinate(
                        subjectOf(rs.getString(1)), gradeOf(rs.getString(2)),
                        termOf(rs.getString(3)), publisherOf(rs.getString(4)),
                        rs.getString(5)),
                editionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<KnowledgePoint> loadKnowledgePoints(
            org.springframework.jdbc.core.JdbcTemplate opsTemplate, EditionCoordinate edition) {
        return opsTemplate.query("""
                        SELECT kp_id::text, subject, grade, canonical_name,
                               COALESCE(aliases, ARRAY[]::text[]) AS aliases
                          FROM rag_knowledge_point
                         WHERE tenant_id = rag_global_tenant_id() AND subject = ? AND grade = ?
                        """,
                (rs, i) -> {
                    String[] raw = rs.getObject("aliases", String[].class);
                    String[] aliases = raw == null ? new String[0] : raw;
                    return new KnowledgePoint(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), List.of(aliases), List.of(), List.of());
                },
                edition.subject().displayName(), edition.grade().displayName());
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
