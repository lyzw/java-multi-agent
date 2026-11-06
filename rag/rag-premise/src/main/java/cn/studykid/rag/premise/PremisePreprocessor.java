package cn.studykid.rag.premise;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.ChunkContext;
import cn.studykid.rag.premise.model.ChunkOutcome;
import cn.studykid.rag.premise.model.ChunkPolicy;
import cn.studykid.rag.premise.model.ChunkPolicyConfig;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.BlockChunker;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * 物料前期处理门面（RAG-D26 §1.3）：一次调用 = 解析 + 切分 + 留痕。
 *
 * <p><b>⚠️ 职责边界</b>：只编排两段工序，<b>不持久化</b>——
 * 片段落库、嵌入、job 状态回写仍归既有 {@code IngestJobOrchestrator}
 * （阶段 3 才把编排器的解析+切分段替换为本门面）。
 *
 * <p><b>⚠️ 解析后端与切分器是显式注入的函数映射</b>（不是内部 if-else）：
 * 装配方（Spring 配置类）负责「按配置选择实现」，本类只负责「用给定实现跑通工序」。
 * 这样：
 * <ul>
 *   <li>门面不感知配置键名（{@code rag.premise.parse-backend} 等属于装配方）；</li>
 *   <li>新增后端/切分器不需要改门面；</li>
 *   <li>测试可以直接传 lambda（见 {@code PremisePreprocessorTest}）。</li>
 * </ul>
 *
 * <p><b>⚠️ 选错策略的失败是即刻的</b>：{@link #parse} / {@link #chunk}
 * 在查表 miss 时抛异常，<b>不在</b>静默取默认实现——
 * 「配了 hybrid 却用了 heading」正是设计文档 §4 列出的留痕失真风险。
 */
public final class PremisePreprocessor {

    private final DocumentParseBackend parseBackend;
    private final Map<ChunkPolicy, BlockChunker> chunkers;
    private final ChunkPolicyConfig config;

    /**
     * @param parseBackend 解析后端（单一后端；多后端选择在装配方完成）
     * @param chunkers     切分器表（至少一项；按 {@link ChunkPolicy} 查找）
     * @param config       切分参数（两策略共用一份；各自默认在 {@code ChunkPolicyConfig}）
     */
    public PremisePreprocessor(@NonNull DocumentParseBackend parseBackend,
                               @NonNull Map<ChunkPolicy, BlockChunker> chunkers,
                               @NonNull ChunkPolicyConfig config) {
        if (parseBackend == null) {
            throw new IllegalArgumentException("parseBackend 不能为空");
        }
        if (chunkers == null || chunkers.isEmpty()) {
            throw new IllegalArgumentException("chunkers 不能为空");
        }
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.parseBackend = parseBackend;
        this.chunkers = Map.copyOf(chunkers);
        this.config = config;
    }

    /**
     * 解析工序。
     *
     * @param documentId 文档 ID
     * @param content    文档内容
     * @return 归一化文档（含解析留痕）
     */
    public @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        return parseBackend.parse(documentId, content);
    }

    /**
     * 切分工序。
     *
     * @param document 归一化文档
     * @param policy   切分策略（查表键）
     * @param context  切分上下文（版本坐标 + 知识点表）
     * @return 切分输出
     */
    public @NonNull ChunkOutcome chunk(@NonNull PremiseDocument document,
                                       @NonNull ChunkPolicy policy,
                                       @NonNull ChunkContext context) {
        BlockChunker chunker = chunkers.get(policy);
        if (chunker == null) {
            throw new IllegalStateException(
                    "切分器未装配: " + policy + "（已装配: " + chunkers.keySet() + "）");
        }
        return chunker.chunk(document, config, context);
    }

    /**
     * 完整工序（解析 + 切分）。
     *
     * @param documentId 文档 ID
     * @param content    文档内容
     * @param policy     切分策略
     * @param context    切分上下文
     * @return 切分输出
     */
    public @NonNull ChunkOutcome process(@NonNull String documentId,
                                         @NonNull byte[] content,
                                         @NonNull ChunkPolicy policy,
                                         @NonNull ChunkContext context) {
        PremiseDocument document = parse(documentId, content);
        return chunk(document, policy, context);
    }

    /**
     * @return 当前解析后端标识（留痕 / 装配自检用）
     */
    public @NonNull String parseBackendId() {
        return parseBackend.backendId();
    }

    /**
     * @return 已装配的切分策略集合（装配自检用）
     */
    public @NonNull List<ChunkPolicy> availablePolicies() {
        return List.copyOf(chunkers.keySet());
    }

    /**
     * @return 当前切分参数
     */
    public @NonNull ChunkPolicyConfig config() {
        return config;
    }

    /**
     * 构造辅助：单切分器的便捷重载（多数装配场景只有一个真实策略 + 占位策略）。
     *
     * @param parseBackend 解析后端
     * @param primary      主切分器
     * @param config       切分参数
     * @return 门面实例
     */
    public static @NonNull PremisePreprocessor of(@NonNull DocumentParseBackend parseBackend,
                                                  @NonNull BlockChunker primary,
                                                  @NonNull ChunkPolicyConfig config) {
        return new PremisePreprocessor(parseBackend,
                Map.of(primary.policy(), primary), config);
    }

    /**
     * 构造辅助：以函数表切分（与 {@link #chunk} 的查表语义一致）。
     *
     * @param parseBackend 解析后端
     * @param chunkerFn    策略 → 切分器函数（miss 返回 null 即抛错）
     * @param config       切分参数
     * @return 门面实例
     */
    public static @NonNull PremisePreprocessor withLookup(
            @NonNull DocumentParseBackend parseBackend,
            @NonNull Function<ChunkPolicy, BlockChunker> chunkerFn,
            @NonNull ChunkPolicyConfig config) {
        Map<ChunkPolicy, BlockChunker> table = new java.util.HashMap<>();
        for (ChunkPolicy policy : ChunkPolicy.values()) {
            BlockChunker chunker = chunkerFn.apply(policy);
            if (chunker != null) {
                table.put(policy, chunker);
            }
        }
        return new PremisePreprocessor(parseBackend, table, config);
    }
}
