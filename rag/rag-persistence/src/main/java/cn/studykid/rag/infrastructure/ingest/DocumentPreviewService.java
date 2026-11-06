package cn.studykid.rag.infrastructure.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.rag.application.ingest.IngestionPipeline;
import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 上传前解析预览（dry-run，RAG-D15 §六 待办 #6）。
 *
 * <p><b>这一层解决什么</b>：改造前的流程是"上传 → 落桶 → 建 job → 异步切分 → 看结果"，
 * 发现切分有问题（答案区误判、公式乱码、题号丢失）时已经产生了：
 * 对象存储里的一份文件、一条 document 行、一条 job 记录。重传要再走一遍完整链路。
 *
 * <p>本类让录入员在**上传前**看到"这本书会被切成什么样"，不满意直接改文件重传。
 *
 * <p><b>⚠️ 绝对的硬约束：dry-run 不得产生任何副作用</b>：
 * <ul>
 *   <li>不写对象存储；</li>
 *   <li>不写 {@code rag_document} / {@code rag_chunk} / {@code rag_ingest_job}；</li>
 *   <li>不嵌入、不建向量。</li>
 * </ul>
 * 它只做<b>一次纯内存的解析 + 切分</b>——{@link IngestionPipeline} 本身是纯函数
 * （不依赖任何 Repository / JdbcTemplate），这是本功能能成立的根本原因。
 *
 * <p><b>⚠️ 为什么"预览结果"必须与真实入库一致</b>：
 * 若预览用一套切分逻辑、入库用另一套，预览就成了误导。
 * 故本类**直接复用** {@link IngestionPipeline} 与
 * {@link JdbcIngestSourceLoader} 的版本坐标/知识点加载，不另写一份。
 */
public class DocumentPreviewService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPreviewService.class);

    /** 默认预览条数上限（大册 2400+ 片段，全量返回会撑爆响应体）。 */
    public static final int DEFAULT_PREVIEW_LIMIT = 50;

    /** 预览结果条数上限（防止调用方传个巨大值）。 */
    public static final int MAX_PREVIEW_LIMIT = 200;

    private final IngestionPipeline pipeline;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * ⚠️ **刻意不依赖 {@code JdbcIngestSourceLoader}**：
     * 那个加载器还负责"按 objectRef 读原始字节"，而读字节的能力由各模块自行注入
     * （worker 用本地桶、bootstrap 无此能力）。预览**只需要版本坐标与知识点**，
     * 不该把"读字节"这条依赖一并拉进来——否则会逼着每个装配方都提供对象读取实现。
     *
     * <p>（这正是初始实现导致 bootstrap 启动失败的原因：
     * {@code JdbcIngestSourceLoader} 在全仓**不是 bean**，
     * 只在 worker 里就地 new 出来。）
     */
    public DocumentPreviewService(
            @NonNull IngestionPipeline pipeline,
            org.springframework.jdbc.core.@NonNull JdbcTemplate jdbcTemplate,
            org.springframework.transaction.support.@NonNull TransactionTemplate transactionTemplate) {
        this.pipeline = pipeline;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 预览切片结果。
     *
     * @param tenantId 租户 ID
     * @param sourceId 来源 ID（版本坐标由它反查）
     * @param fileName 文件名（仅用于打日志与识别解析器输入）
     * @param content  文件字节
     * @param limit    返回片段条数上限
     * @return 预览结果
     * @throws IllegalStateException 来源未登记版本五轴（无法确定切分用的版本坐标）
     */
    public @NonNull PreviewResult preview(@NonNull String tenantId,
                                          @NonNull String sourceId,
                                          @NonNull String fileName,
                                          @NonNull byte[] content,
                                          int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_PREVIEW_LIMIT));

        // ⚠️ 取不到版本坐标就明确失败，**不造默认坐标**：
        //    造默认坐标会让预览"成功"，而真实入库若也没坐标，载体同样挂到默认坐标——
        //    预览就变成了"确认一个错误的结果"。宁可让人看到"该来源未登记五轴"。
        var ctx = loadPreviewContext(tenantId, sourceId).orElseThrow(() ->
                new IllegalStateException(
                        "该来源未登记版本五轴，无法预览切分结果"
                                + "（请先在「来源登记」补齐学科/年级/册次/出版社/版次）"));

        // ⚠️ documentId 必须**随内容变化**：chunkId 由
        //    「documentId + 页码 + charStart」派生。若用固定占位，
        //    两份不同的文件会得到相同的 chunkId —— 虽然不落库，
        //    但界面上的 ID 会误导人（"这两个片段是同一个"）。
        //    用内容哈希派生：同一份文件多次预览 ID 稳定（便于比对），
        //    不同文件必然不同。
        String documentId = "preview-" + contentDigest(content);

        List<Chunk> chunks = pipeline.ingest(
                documentId, fileName, ctx.edition(), content, ctx.knowledgePoints());

        List<Chunk> preview = new ArrayList<>();
        for (int i = 0; i < chunks.size() && i < effectiveLimit; i++) {
            preview.add(chunks.get(i));
        }

        Map<String, Integer> byRole = countByRole(chunks);
        log.info("解析预览 tenant={} source={} file={} chunks={} preview={}",
                tenantId, sourceId, fileName, chunks.size(), preview.size());

        return new PreviewResult(
                chunks.size(), preview.size(), effectiveLimit, byRole,
                ctx.edition(), preview, chunks.size() > effectiveLimit);
    }

    /** 预览上下文：版本坐标 + 知识点。 */
    private record PreviewContext(@NonNull EditionCoordinate edition,
                                  @NonNull List<KnowledgePoint> knowledgePoints) {
    }

    /**
     * 加载版本坐标与知识点（预览专用）。
     *
     * <p>⚠️ 取不到版本坐标返回空，**不造默认坐标**：造默认坐标会让预览"成功"，
     * 而真实入库若也没坐标，载体同样挂到默认坐标——预览就成了"确认一个错误的结果"。
     *
     * <p>⚠️ 与 {@code JdbcIngestSourceLoader} 必须同口径（否则预览与入库对不上）：
     * ① 知识点只取**同学科同年级**（跨学科挂接会产生错误归因）；
     * ② subject/grade 传 **displayName 字符串**而非枚举对象
     * （JDBC 无法为枚举实例推断 SQL 类型，会抛「无法推测 SQL 类型」）。
     */
    private java.util.Optional<PreviewContext> loadPreviewContext(@NonNull String tenantId,
                                                                  @NonNull String sourceId) {
        return transactionTemplate.execute(status -> {
            // ⚠️ RLS 需要会话租户，且 set_config 是事务局部的。
            jdbcTemplate.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);

            List<EditionCoordinate> editions = jdbcTemplate.query(
                    "SELECT subject, grade, term, publisher, revision_code"
                            + " FROM rag_edition"
                            + " WHERE tenant_id = ?::uuid AND source_id = ?::uuid"
                            + " ORDER BY created_at ASC, edition_id LIMIT 1",
                    (rs, i) -> new EditionCoordinate(
                            subjectOf(rs.getString(1)), gradeOf(rs.getString(2)),
                            termOf(rs.getString(3)), publisherOf(rs.getString(4)),
                            rs.getString(5)),
                    tenantId, sourceId);
            if (editions.isEmpty()) {
                return java.util.Optional.<PreviewContext>empty();
            }
            EditionCoordinate edition = editions.get(0);

            List<KnowledgePoint> kps = jdbcTemplate.query(
                    "SELECT kp_id::text, subject, grade, canonical_name,"
                            + " COALESCE(aliases, ARRAY[]::text[]) AS aliases"
                            + " FROM rag_knowledge_point"
                            + " WHERE tenant_id = ?::uuid AND subject = ? AND grade = ?",
                    (rs, i) -> {
                        String[] raw = rs.getObject("aliases", String[].class);
                        String[] aliases = raw == null ? new String[0] : raw;
                        return new KnowledgePoint(
                                rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), List.of(aliases), List.of(), List.of());
                    },
                    tenantId, edition.subject().displayName(), edition.grade().displayName());

            return java.util.Optional.of(new PreviewContext(edition, kps));
        });
    }

    // 枚举反查（与 JdbcIngestSourceLoader 同口径：库内存 displayName，用 name() 会全抛未知值）
    private static EditionCoordinate.Subject subjectOf(String s) {
        for (var v : EditionCoordinate.Subject.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知学科值：" + s);
    }

    private static EditionCoordinate.GradeBand gradeOf(String s) {
        for (var v : EditionCoordinate.GradeBand.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知年级值：" + s);
    }

    private static EditionCoordinate.Term termOf(String s) {
        for (var v : EditionCoordinate.Term.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知册次值：" + s);
    }

    private static EditionCoordinate.Publisher publisherOf(String s) {
        for (var v : EditionCoordinate.Publisher.values()) {
            if (v.displayName().equals(s)) {
                return v;
            }
        }
        throw new IllegalStateException("未知出版社值：" + s);
    }

    /** 内容摘要（SHA-256 前 16 位十六进制），仅用于派生稳定的预览 ID。 */
    private static String contentDigest(byte[] content) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SPI 加载器必须可用；不可用说明运行环境异常，不应静默降级。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Map<String, Integer> countByRole(List<Chunk> chunks) {
        Map<String, Integer> map = new LinkedHashMap<>();
        // ⚠️ 先按枚举声明顺序预置 0：让界面稳定展示全部角色，
        //    而不是"只有出现过的角色才显示"（后者会让两次预览的图例跳来跳去）。
        for (ChunkRole r : ChunkRole.values()) {
            map.put(r.name(), 0);
        }
        for (Chunk c : chunks) {
            map.merge(c.role().name(), 1, Integer::sum);
        }
        return map;
    }

    /**
     * 预览结果。
     *
     * @param totalChunks    切分得到的总片段数
     * @param returnedChunks 本次返回的片段数
     * @param limit          生效的条数上限
     * @param byRole         按角色的片段计数（含计数为 0 的角色）
     * @param edition        预览所用版本坐标
     * @param chunks         片段样本（最多 limit 条）
     * @param truncated      是否因上限被截断
     */
    public record PreviewResult(
            int totalChunks,
            int returnedChunks,
            int limit,
            @NonNull Map<String, Integer> byRole,
            @NonNull EditionCoordinate edition,
            @NonNull List<Chunk> chunks,
            boolean truncated
    ) {
    }
}
