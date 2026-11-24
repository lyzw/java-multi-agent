package cn.studykid.rag.application.ingest;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.embedding.EmbeddingInput;
import cn.studykid.rag.core.knowledge.KnowledgePoint;

/**
 * 从切分产物构造 Embedding 模板入参（RAG-D28 P3-1）。
 *
 * <p>⚠️ 单一构造点：租户/全局两个摄取编排器共用本工厂，避免两处拼装漂移——
 * 若一个把题号拼进「位置」、另一个拼进「知识点」，同批语料的向量空间就不一致。
 *
 * <p>⚠️ 知识点名/别名从 {@code KnowledgePoint} 权威对象取值，
 * <b>不</b>用 {@code chunk.knowledgePointIds()}（只有 UUID，进模板既无语义又可能
 * 命中 PII 数字门禁）。未挂知识点的片段传空列表（模板渲染为空知识点行）。
 */
public final class EmbeddingInputFactory {

    private EmbeddingInputFactory() {
    }

    /**
     * @param chunk     切分片段
     * @param points    来源可见的知识点全集（用于把 id 解析为规范名/别名）
     * @return 模板入参
     */
    public static @NonNull EmbeddingInput fromChunk(@NonNull Chunk chunk,
                                                    @NonNull List<KnowledgePoint> points) {
        if (chunk == null || points == null) {
            throw new IllegalArgumentException("chunk/points 不可为空");
        }
        List<KnowledgePoint> matched = new ArrayList<>();
        for (String kpId : chunk.knowledgePointIds()) {
            for (KnowledgePoint kp : points) {
                if (kp.id().equals(kpId)) {
                    matched.add(kp);
                    break;
                }
            }
        }
        // ⚠️ 确定化：按 canonicalName 排序后「名称/别名」同步写出，与重索引侧 SQL
        //    `array_agg(... ORDER BY canonical_name)` 逐字同构。若两侧各自独立排序
        //    （或按 DB 返回顺序），同一片段重索引会产生不同模板文本 → 向量漂移。
        matched.sort(java.util.Comparator.comparing(KnowledgePoint::canonicalName));
        List<String> names = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        for (KnowledgePoint kp : matched) {
            names.add(kp.canonicalName());
            // 每个知识点的别名拼成一个元素（'; ' 分隔），与 SQL 的 array_to_string 同构。
            aliases.add(String.join("; ", kp.aliases()));
        }
        var edition = chunk.edition();
        var locator = chunk.locator();
        return new EmbeddingInput(
                edition.publisher().displayName(),
                edition.grade().displayName(),
                edition.term().displayName(),
                edition.revisionCode(),
                locator.sectionPath(),
                locator.pageNumber(),
                locator.pageNumber(),
                locator.exerciseNo(),
                // blockType 与落库列 rag_chunk.block_type 同源（layoutFacts.blockType），
                // 保证重索引 SQL 读回的 block_type 与首次摄取逐字一致。
                chunk.layoutFacts() == null ? null : chunk.layoutFacts().blockType(),
                chunk.role().name(),
                names,
                aliases,
                chunk.text(),
                // P3-1 语义槽位：切分器已从 PremiseBlock 采集并随 Chunk 带过——
                //   公式走受控 embeddingView（未命中仅 LaTeX，禁自猜），
                //   表格走确定性结构摘要；无来源则 null，模板不编造。
                chunk.formulaNaturalLanguage(),
                chunk.captionOrTableSummary());
    }
}
