package cn.studykid.rag.premise.chunker;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.chunk.LayoutFacts;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.ChunkTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.normalize.ChunkIdentifiers;
import cn.studykid.rag.premise.normalize.SectionPathResolver;
import cn.studykid.rag.premise.role.BlockRoleResolver;

/**
 * 自建「标题层级分块」切分器（RAG-D26 §2.3 / D04 §5.1）。
 *
 * <p><b>⚠️ 主循环与既有 {@code IngestionPipeline.ingest()} 逐条等价</b>：
 * 标题推进章节路径 → 噪声/空白丢弃 → 有效题号 → 角色判定 → 题号推进 → 生成片段。
 * 等价性验收（RAG-D26 阶段 2.1）：对同一批 {@code LayoutBlock}，
 * 本切分器与既有管道的 {@code Chunk[]} <b>逐条全等</b>
 * （id / role / text / locator / kpIds / facts），由
 * {@code HeadingHierarchyChunkerParityTest} 断言。
 *
 * <p><b>超预算拆分是本切分器唯一的「新增」语义</b>（既有管道无此概念）：
 * 单块文本超 {@code maxTokens} 且<b>不在答案区</b>时按比例重叠拆分。
 * 答案区块不拆：把答案内容切成多片会拆散「答案-题号」的版面对应关系，
 * 风险大于收益（保持与既有行为一致：既有管道从不拆块）。
 * 拆分片的 locator 遵守可回取性：各片 {@code [charStart, charEnd)}
 * 按原文截取位置线性映射回页内字符流，拼接覆盖原区间（重叠处重复覆盖）。
 *
 * <p><b>token 度量口径</b>：无分词器依赖时以「UTF-16 代码单元数」近似
 * （{@code String.length()}）。中文场景该近似保守偏大（一个汉字≈1~2 token），
 * 只会更早拆分、不会超嵌入窗口，是安全的方向性偏差；
 * 后续接入真分词器只改 {@link #approxTokenCount(String)}。
 */
public class HeadingHierarchyChunker implements cn.studykid.rag.premise.spi.BlockChunker {

    /** 拆分时的字符/token 换算比（保守值 1.0，见类注释的口径说明）。 */
    static final double CHARS_PER_TOKEN = 1.0;

    @Override
    public @NonNull ChunkPolicy policy() {
        return ChunkPolicy.HEADING_HIERARCHY;
    }

    @Override
    public @NonNull ChunkOutcome chunk(@NonNull PremiseDocument document,
                                       @NonNull ChunkPolicyConfig config,
                                       @NonNull ChunkContext context) {
        String documentId = document.documentId();
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder sectionPath = new StringBuilder();
        String currentExerciseNo = null;
        int droppedNoise = 0;
        int splitOversize = 0;

        for (PremiseBlock premiseBlock : document.blocks()) {
            // PremiseBlock 是 LayoutBlock 的恒等包装：判定规则直接消费被包装块，
            // 与既有管道走同一条代码路径（role/BlockRoleResolver）。
            LayoutBlock block = premiseBlock.toLayoutBlock();

            // ① 标题块推进章节路径
            if (block.type().isHeading()) {
                SectionPathResolver.advance(sectionPath, block);
                currentExerciseNo = null;
                continue;
            }
            // ② 噪声块直接丢弃
            if (block.type().isNoise()) {
                droppedNoise++;
                continue;
            }
            // ②' 空白文本块直接丢弃
            //    ⚠️ 与既有管道同口径（2026-11-03 实测踩坑记录）：
            //    LayoutBlock 只拒绝 text==null（纯空白能通过），
            //    Chunk 拒绝 text.isBlank()——空白块若不跳过会让整册摄取失败。
            if (block.text() == null || block.text().isBlank()) {
                droppedNoise++;
                continue;
            }
            // ③ 有效题号＝块自身题号，否则继承上文最近一次出现的题号
            //    ⚠️ 必须**先**算出有效题号再判角色（顺序即安全顺序，见 resolver 注释）。
            String effectiveExerciseNo =
                    BlockRoleResolver.effectiveExerciseNo(block, currentExerciseNo);
            // ④ 角色判定（版面事实优先，前缀兜底）
            ChunkRole role = BlockRoleResolver.resolveRole(block, effectiveExerciseNo);
            if (role == ChunkRole.BOILERPLATE) {
                droppedNoise++;
                continue;
            }
            // ⑤ 题号推进
            if (block.exerciseNo() != null) {
                currentExerciseNo = block.exerciseNo();
            }
            // ⑥ 版面事实快照（与判定同源，供校对页展示「依据什么下的结论」）
            LayoutFacts facts = new LayoutFacts(
                    block.inAnswerZone(),
                    block.type() == null ? null : block.type().name(),
                    BlockRoleResolver.matchedSolutionPrefix(block.text()));

            // ⑦ 生成片段（超预算且不在答案区时拆分）
            int page = block.pageNumber();
            String resolvedPath = SectionPathResolver.resolve(sectionPath, block);
            String text = block.text();

            if (approxTokenCount(text) > config.maxTokens() && !block.inAnswerZone()) {
                splitOversize++;
                for (ChunkPiece piece : splitWithOverlap(text, config)) {
                    chunks.add(newChunk(documentId, context, piece.text(), page, resolvedPath,
                            effectiveExerciseNo, currentExerciseNo, facts, role,
                            page, block.charStart() + piece.start(),
                            block.charStart() + piece.end()));
                }
            } else {
                chunks.add(newChunk(documentId, context, text, page, resolvedPath,
                        effectiveExerciseNo, currentExerciseNo, facts, role,
                        page, block.charStart(), block.charEnd()));
            }
        }

        ChunkTrace trace = new ChunkTrace(ChunkPolicy.HEADING_HIERARCHY, config.stableString(),
                document.blocks().size(), chunks.size(), droppedNoise, splitOversize);
        return new ChunkOutcome(documentId, ChunkPolicy.HEADING_HIERARCHY, config, chunks, trace);
    }

    // ---------- 片段构造 ----------

    private Chunk newChunk(String documentId, ChunkContext context, String rawText, int page,
                           String sectionPath, String effectiveExerciseNo,
                           String currentExerciseNo, LayoutFacts facts,
                           ChunkRole wholeBlockRole,
                           int locatorPage, int charStart, int charEnd) {
        String trimmed = rawText.trim();
        // ⚠️ 片级角色 = 片级前缀兜底判定，未命中时回落整块角色（主循环判定结果）。
        //    两个判定入口是同一张表（BlockRoleResolver），拆分片可能落在前缀之后
        //    （如「解：」被切走），片级独立判定才能保证片级同样不泄题。
        ChunkRole role = BlockRoleResolver
                .resolveAnswerBearingByContent(trimmed, effectiveExerciseNo);
        Chunk.Locator locator = new Chunk.Locator(locatorPage, sectionPath,
                currentExerciseNo, charStart, charEnd);
        return new Chunk(
                ChunkIdentifiers.chunkId(documentId, locatorPage, charStart),
                documentId,
                context.edition(),
                role != null ? role : wholeBlockRole,
                trimmed,
                locator,
                matchedKpIds(trimmed, context),
                currentExerciseNo,
                ChunkIdentifiers.contentHash(trimmed),
                facts);
    }

    private List<String> matchedKpIds(String text, ChunkContext context) {
        List<String> matched = new ArrayList<>();
        List<KnowledgePoint> kps = context.knowledgePoints();
        if (kps.isEmpty()) {
            return matched;
        }
        String normalized = KnowledgePoint.normalize(text);
        for (KnowledgePoint kp : kps) {
            if (kp.matches(normalized)) {
                matched.add(kp.id());
            }
        }
        return matched;
    }

    // ---------- token 近似与重叠拆分 ----------

    /**
     * token 数近似（UTF-16 代码单元，见类注释的口径说明）。
     *
     * @param text 文本
     * @return 近似 token 数
     */
    static int approxTokenCount(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 带重叠的确定性拆分：按
     * {@code window = maxTokens × CHARS_PER_TOKEN}、
     * {@code step = window × (1 - overlapRatio)} 滑窗，
     * 末片不足 window 一半时并入前片（避免碎尾片）。
     *
     * @param text   原文
     * @param config 参数
     * @return 拆分片（顺序即原文顺序）
     */
    static @NonNull List<ChunkPiece> splitWithOverlap(@NonNull String text,
                                                 @NonNull ChunkPolicyConfig config) {
        int window = (int) Math.floor(config.maxTokens() * CHARS_PER_TOKEN);
        int step = (int) Math.floor(window * (1.0 - config.overlapRatio()));
        if (step < 1) {
            step = 1;
        }
        List<ChunkPiece> parts = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + window);
            parts.add(new ChunkPiece(text.substring(start, end), start, end));
            if (end == text.length()) {
                break;
            }
        }
        // 碎尾片并入前片（尾片 < window/2 时）：扩前片的文本与 end，不产生孤立短片
        if (parts.size() >= 2) {
            ChunkPiece last = parts.get(parts.size() - 1);
            if (last.text().length() < window / 2) {
                ChunkPiece prev = parts.get(parts.size() - 2);
                parts.set(parts.size() - 2,
                        new ChunkPiece(prev.text() + last.text(), prev.start(), last.end()));
                parts.remove(parts.size() - 1);
            }
        }
        return parts;
    }
}
