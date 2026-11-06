package cn.studykid.rag.application.ingest;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.ChunkRole;
import cn.studykid.rag.core.chunk.LayoutFacts;
import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.core.version.EditionCoordinate;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.ports.ParsedDocument;
import cn.studykid.rag.premise.normalize.ChunkIdentifiers;
import cn.studykid.rag.premise.normalize.SectionPathResolver;
import cn.studykid.rag.premise.role.BlockRoleResolver;

/**
 * 入库切分用例（RAG-D04 §3）。
 *
 * <p><b>⚠️ 切分质量是教辅 RAG 的第一瓶颈</b>，比检索算法更关键。
 * 检索算法再好，解析时把公式 OCR 成乱码、丢掉题号、
 * 把答案区混进正文，召回就必然出错——而且<b>这种错误在评测里
 * 表现为「召回不全」而非「答错」</b>，极易被归因错误地漏检。
 *
 * <p><b>切分策略：结构优先，语义兜底</b>。
 * <ul>
 *   <li><b>结构切分</b>（首选）：以版面结构为边界——
 *       标题块开启新节，题目块自成单元，答案块自成单元。</li>
 *   <li><b>语义兜底</b>：结构不可得时，退化为带重叠的固定窗口切分，
 *       且<b>重叠量必须大于 0</b>——教辅的定义与适用条件常被窗口切开。</li>
 * </ul>
 *
 * <p><b>⚠️ 角色判定基于版面，不基于模型</b>：
 * {@code inAnswerZone} 与 {@code exerciseNo} 来自解析器的版面识别。
 * 若用模型判断角色，模型会把「讲评」误判为「答案」——
 * 而误判为答案的代价是<b>泄题</b>，误判为讲解的代价仅是<b>不够具体</b>。
 * 两类错误代价不对称，因此<b>宁可过判为讲解</b>（更安全），
 * 并要求人工抽检（见 RAG-D08 §5）。
 *
 * <p><b>⚠️ 规则已迁移至 rag-premise（RAG-D26 阶段 0.3）</b>：
 * 角色判定表（{@code BlockRoleResolver}）、章节路径推进/取值
 * （{@code SectionPathResolver}）、chunk 标识与指纹
 * （{@code ChunkIdentifiers})现在统一住在 {@code rag-premise}，
 * 本类<b>委托调用</b>。迁移是「搬运不是重写」：
 * 判定语义与迁移前逐条等价，由既有测试（本模块与 infrastructure 的
 * 全部角色/路径用例）<b>一条不改地通过</b>来保证——
 * 这是「切分规则只有一张判定表、两处实现不再漂移」的结构性保障。
 *
 * <p><b>⚠️ 双模式（RAG-D26 阶段 3.1，默认关闭）</b>：
 * 本类可注入 {@code BlockChunker}（{@code rag-premise} 的切分 SPI），
 * 注入后 {@link #ingest} 走「解析 → 归一化 → 切分器」的新管线；
 * <b>不注入时行为与迁移前完全一致</b>（内置主循环，旧测试锚定）。
 * 切换由装配方控制（{@code rag.premise.enabled}），
 * 灰度期间两种模式共用同一份 {@code parserPort}——
 * 只有切分方式不同，评测才能把差异归因到切分策略本身。
 */
public class IngestionPipeline {

    private final DocumentParserPort parserPort;

    /**
     * 可选的 premise 切分器（null = 内置旧逻辑）。
     *
     * <p>⚠️ {@code BlockChunker} 的实现可能 fail-closed（如 HybridChunker 占位），
     * 装配方必须保证注入的实现真实可用——本类不做回退。
     */
    private final cn.studykid.rag.premise.spi.BlockChunker premiseChunker;

    private final cn.studykid.rag.premise.model.ChunkPolicyConfig premiseConfig;

    /**
     * @param parserPort 解析端口（旧路径与新路径共用）
     */
    public IngestionPipeline(DocumentParserPort parserPort) {
        this(parserPort, null, null);
    }

    /**
     * premise 模式构造（阶段 3.1 灰度开关的注入点）。
     *
     * @param parserPort      解析端口
     * @param premiseChunker  premise 切分器（非 null 时启用新管线）
     * @param premiseConfig   切分参数（premise 模式必填）
     */
    public IngestionPipeline(DocumentParserPort parserPort,
                             cn.studykid.rag.premise.spi.BlockChunker premiseChunker,
                             cn.studykid.rag.premise.model.ChunkPolicyConfig premiseConfig) {
        if (parserPort == null) {
            throw new IllegalArgumentException("parserPort 不能为空");
        }
        if (premiseChunker != null && premiseConfig == null) {
            throw new IllegalArgumentException(
                    "注入 premiseChunker 时必须同时提供 premiseConfig");
        }
        this.parserPort = parserPort;
        this.premiseChunker = premiseChunker;
        this.premiseConfig = premiseConfig;
    }

    /**
     * @return 是否启用 premise 切分管线（装配自检与测试断言用）
     */
    public boolean premiseModeEnabled() {
        return premiseChunker != null;
    }

    /**
     * @return 当前切分策略标识（写入 {@code chunk_policy} 留痕列）；
     *         旧模式返回 null——「未采集」语义，不伪造策略值
     */
    public String chunkPolicyTrace() {
        return premiseChunker == null ? null : premiseChunker.policy().name();
    }

    /**
     * @return 当前切分参数快照（写入 {@code chunk_policy_config} 留痕列）；
     *         旧模式返回 null
     */
    public String chunkPolicyConfigTrace() {
        return premiseConfig == null ? null : premiseConfig.stableString();
    }

    /**
     * 解析并切分文档。
     *
     * @param documentId 文档 ID
     * @param documentTitle 文档标题
     * @param edition 版本坐标
     * @param content 文档内容
     * @param knowledgePoints 知识点映射表（用于挂接知识点）
     * @return 切分后的片段
     */
    public @NonNull List<Chunk> ingest(@NonNull String documentId,
                                       @NonNull String documentTitle,
                                       @NonNull EditionCoordinate edition,
                                       @NonNull byte[] content,
                                       @NonNull List<KnowledgePoint> knowledgePoints) {
        ParsedDocument parsed = parserPort.parse(documentId, content);
        // 阶段 3.1 双模式：注入了 premise 切分器 → 走归一化 + 切分 SPI；
        // 否则走内置主循环（与迁移前行为逐条等价，旧测试锚定）。
        if (premiseChunker != null) {
            return ingestViaPremise(documentId, edition, knowledgePoints, parsed);
        }
        return ingestLegacyLoop(documentId, edition, knowledgePoints, parsed);
    }

    /**
     * premise 切分管线（阶段 3.1 新路径）。
     *
     * <p>解析产物经 {@code PremiseDocument.fromParsed} 恒等包装（无字段复制），
     * 切分策略由注入的 {@code premiseChunker} 决定；知识点挂接经由
     * {@code ChunkContext} 传入切分器（挂接规则与旧路径同源）。
     *
     * @param documentId      文档 ID
     * @param edition         版本坐标
     * @param knowledgePoints 知识点匹配表
     * @param parsed          既有端口解析产物
     * @return 切分片段
     */
    private @NonNull List<Chunk> ingestViaPremise(@NonNull String documentId,
                                                  @NonNull EditionCoordinate edition,
                                                  @NonNull List<KnowledgePoint> knowledgePoints,
                                                  @NonNull ParsedDocument parsed) {
        cn.studykid.rag.premise.model.PremiseDocument premise =
                cn.studykid.rag.premise.model.PremiseDocument.fromParsed(documentId, parsed);
        cn.studykid.rag.premise.model.ChunkOutcome outcome = premiseChunker.chunk(
                premise,
                premiseConfig,
                new cn.studykid.rag.premise.model.ChunkContext(edition, knowledgePoints));
        return outcome.chunks();
    }

    /**
     * 内置旧主循环（默认路径；与迁移前行为逐条等价）。
     */
    private @NonNull List<Chunk> ingestLegacyLoop(@NonNull String documentId,
                                                  @NonNull EditionCoordinate edition,
                                                  @NonNull List<KnowledgePoint> knowledgePoints,
                                                  @NonNull ParsedDocument parsed) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder sectionPath = new StringBuilder();
        String currentExerciseNo = null;

        for (LayoutBlock block : parsed.blocks()) {
            // ① 标题块推进章节路径
            if (block.type().isHeading()) {
                // 委托 rag-premise 的 SectionPathResolver（RAG-D26 阶段 0.3 迁移）：
                // 规则与原 updateSectionPath 逐行等价（按层级截断而非追加）。
                SectionPathResolver.advance(sectionPath, block);
                currentExerciseNo = null;
                continue;
            }
            // ② 噪声块直接丢弃
            if (block.type().isNoise()) {
                continue;
            }
            // ②' 空白文本块直接丢弃
            //    ⚠️ 两处空校验口径不一致（2026-11-03 实测踩中）：
            //    LayoutBlock 只拒绝 text==null（纯空白能通过），
            //    Chunk 拒绝 text.isBlank() —— 于是 Docling 产出的
            //    空白块（图片占位/空行，很常见）会让**整册摄取失败**
            //    （报错「text 不能为空」，162 页教材在切分阶段整本挂掉）。
            //    空白块不含任何内容，跳过即可（与「噪声块丢弃」同义）。
            if (block.text() == null || block.text().isBlank()) {
                continue;
            }
            // ③ 有效题号＝块自身题号，否则继承上文最近一次出现的题号
            //    ⚠️ 必须**先**算出有效题号再判角色：
            //    答案册里的答案块通常**不带自己的题号**，
            //    它归属的是上文那道题。若只看块自身题号，
            //    「习题的答案」会被判成「例题解答」甚至「讲解」——
            //    后者会让标准答案在讲解模式下被召回（泄题）。
            //    委托 rag-premise 的 BlockRoleResolver（RAG-D26 阶段 0.3 迁移）。
            String effectiveExerciseNo =
                    BlockRoleResolver.effectiveExerciseNo(block, currentExerciseNo);
            // ④ 答案区判定——先于角色推断，且优先
            //    （委托同一 resolver：判定表与迁移前逐条等价）。
            ChunkRole role = BlockRoleResolver.resolveRole(block, effectiveExerciseNo);
            if (role == ChunkRole.BOILERPLATE) {
                continue;
            }
            // ⑤ 题号推进
            if (block.exerciseNo() != null) {
                currentExerciseNo = block.exerciseNo();
            }
            // ⑥ 生成片段
            //    ⚠️ 章节路径取值见 SectionPathResolver.resolve：标题上下文优先，
            //    无标题上下文时回退到解析器给出的路径。
            Chunk.Locator locator = new Chunk.Locator(
                    block.pageNumber(),
                    SectionPathResolver.resolve(sectionPath, block),
                    currentExerciseNo,
                    block.charStart(),
                    block.charEnd());
            List<String> kpIds = matchKnowledgePoints(block.text(), knowledgePoints);
            // ⑦ 版面事实快照（RAG-D13 §3.2.4）
            //    ⚠️ 必须在这里**采集**而不是事后重算：解析器版本升级后，
            //       同一片段的重算结果可能不同；存快照才能解释"历史上为什么那样判"。
            //    ⚠️ matchedPrefix 记录**具体命中的词**（如「解：」）而非布尔：
            //       校对员要能一眼看到"是哪个前缀让系统判成答案的"。
            //    采集与判定使用同一份规则（BlockRoleResolver.matchedSolutionPrefix，
            //    RAG-D26 阶段 0.3 迁移）。
            LayoutFacts facts = new LayoutFacts(
                    block.inAnswerZone(),
                    block.type() == null ? null : block.type().name(),
                    BlockRoleResolver.matchedSolutionPrefix(block.text()));
            chunks.add(new Chunk(
                    ChunkIdentifiers.chunkId(documentId, block.pageNumber(), block.charStart()),
                    documentId,
                    edition,
                    role,
                    block.text().trim(),
                    locator,
                    kpIds,
                    currentExerciseNo,
                    ChunkIdentifiers.contentHash(block.text()),
                    facts));
        }
        return List.copyOf(chunks);
    }

    /**
     * 知识点匹配。
     *
     * <p>⚠️ 当前实现为<b>别名/规范名的确定性匹配</b>，不做模型抽取。
     * 理由：知识点挂错的后果是「检索到错误知识点的内容」，
     * 而模型抽取的误挂率不可控且不可复现。
     * 模型抽取作为<b>辅助信号</b>引入时，必须与确定性匹配<b>并存</b>，
     * 且其结果标记为「低置信」，不得单独决定挂接（RAG-D04 §3.4）。
     */
    private List<String> matchKnowledgePoints(String text, List<KnowledgePoint> knowledgePoints) {
        String normalized = KnowledgePoint.normalize(text);
        List<String> matched = new ArrayList<>();
        for (KnowledgePoint kp : knowledgePoints) {
            if (kp.matches(normalized)) {
                matched.add(kp.id());
            }
        }
        return matched;
    }
}
