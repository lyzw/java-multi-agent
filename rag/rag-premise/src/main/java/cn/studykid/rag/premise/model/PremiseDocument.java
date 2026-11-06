package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;

/**
 * 解析工序的统一输出（RAG-D26 §1.3）。
 *
 * <p><b>⚠️ 本类型是「归一化」的产物</b>：无论解析后端是 Docling、MinerU 还是
 * PP-StructureV3，进入切分工序前都必须先被映射为 {@link PremiseBlock} 序列。
 * 这样两个切分器（标题层级 / HybridChunker）看到的<b>永远是同一份输入</b>，
 * 「换解析器」与「换切分器」的评测才能正交进行。
 *
 * <p><b>与既有 {@link ParsedDocument} 的关系</b>：
 * {@link PremiseBlock} 内部直接持有 {@link LayoutBlock} 实例（不是逐字段复制），
 * 因此 {@link #toParsedDocument()} 的降维是<b>恒等映射</b>——
 * 从根本上杜绝「两套词汇表字段漂移」这一 RAG-D26 §4 列出的风险。
 * 既有 {@code IngestionPipeline} 在过渡期继续消费 {@link ParsedDocument}（兼容兜底）。
 *
 * <p><b>doclingJsonRef（分块桥的数据流约束，§2.4 纪律 ①）</b>：
 * HybridChunker 必须复用<b>解析阶段</b>的 DoclingDocument JSON——
 * 若解析后端是 Docling，包装时把原始 json_content 存进此字段；
 * 其他后端为 null（HybridChunker 对此类文档直接 fail-closed）。
 * 存进 record 是为了「解析产物与分块输入是同一份」可被编译期保证——
 * 中途丢失/二次解析都会让 item_refs 与块对不上。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见
 * {@code rag-ports} 的 {@code NestedTypeImportGuardTest}（RAG-D09 §6）。
 *
 * @param documentId     文档 ID
 * @param blocks         归一化版面块序列（阅读顺序）
 * @param trace          解析留痕（后端、版本、页数、告警）
 * @param doclingJsonRef 解析阶段的原始 DoclingDocument JSON（仅 Docling 后端非空；
 *                       分块桥复用它做 chunking，禁止二次解析）
 */
public record PremiseDocument(
        @NonNull String documentId,
        @NonNull List<PremiseBlock> blocks,
        @NonNull ParseTrace trace,
        String doclingJsonRef) {

    public PremiseDocument {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (trace == null) {
            throw new IllegalArgumentException("trace 不能为空");
        }
    }

    /** 主构造（无 docling JSON——MinerU / PP 等非 Docling 后端）。 */
    public PremiseDocument(@NonNull String documentId,
                           @NonNull List<PremiseBlock> blocks,
                           @NonNull ParseTrace trace) {
        this(documentId, blocks, trace, null);
    }

    /**
     * 从既有端口的解析产物构造（兼容路径：Docling / 占位解析器仍走
     * {@code DocumentParserPort}，此处只做包装，不做任何语义改动）。
     *
     * @param documentId 文档 ID
     * @param parsed     既有端口产物
     * @return 归一化文档（留痕标记为 untracked）
     */
    public static @NonNull PremiseDocument fromParsed(@NonNull String documentId,
                                                      @NonNull ParsedDocument parsed) {
        return fromParsed(documentId, parsed, ParseTrace.untracked());
    }

    /**
     * 从既有端口的解析产物构造，并显式携带留痕。
     *
     * @param documentId 文档 ID
     * @param parsed     既有端口产物
     * @param trace      解析留痕
     * @return 归一化文档
     */
    public static @NonNull PremiseDocument fromParsed(@NonNull String documentId,
                                                      @NonNull ParsedDocument parsed,
                                                      @NonNull ParseTrace trace) {
        List<PremiseBlock> blocks = parsed.blocks().stream()
                .map(PremiseBlock::fromLayoutBlock)
                .toList();
        return new PremiseDocument(documentId, blocks, trace);
    }

    /**
     * 降维为既有端口类型（恒等映射：{@link PremiseBlock} 内部持有的就是
     * {@link LayoutBlock} 实例，此处只是解包，无字段复制）。
     *
     * @return 既有端口的结构化文档
     */
    public @NonNull ParsedDocument toParsedDocument() {
        List<LayoutBlock> raw = blocks.stream()
                .map(PremiseBlock::toLayoutBlock)
                .toList();
        return new ParsedDocument(documentId, raw);
    }

    /**
     * @return 文档中出现的最大物理页码（空文档为 0；与 DDL 的 {@code page_count >= 0} 对齐）
     */
    public int pageCount() {
        return blocks.stream().mapToInt(PremiseBlock::pageNumber).max().orElse(0);
    }
}
