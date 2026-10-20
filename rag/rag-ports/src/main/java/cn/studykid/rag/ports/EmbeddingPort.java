package cn.studykid.rag.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;

/**
 * 嵌入与重排端口（RAG-D02 §3.5）。
 *
 * <p><b>⚠️ 模型选型约束</b>：教辅语料同时包含中文教材与英文论文，
 * 且含大量<b>公式、术语、专有名词</b>。因此：
 * <ul>
 *   <li>嵌入模型需支持<b>中英混合</b>与<b>长文本</b>；</li>
 *   <li>需要<b>稀疏（词法）通道</b>：教材术语（「二次函数顶点」）的精确匹配
 *       是关键词问题，纯稠密向量会把「二次函数」与「反二次函数」混淆；</li>
 *   <li>重排器（cross-encoder）精度显著高于双塔，是召回质量的决定性环节。</li>
 * </ul>
 * BGE-M3 一类同时提供稠密 + 稀疏 + 多向量三种检索模式且支持 100+ 语言的
 * 编码器，与上述约束吻合。
 *
 * <p><b>⚠️ 确定性要求</b>：嵌入与重排结果参与最终分数，
 * 因此模型<b>版本必须随索引一起记录</b>。换了模型必须重建索引，
 * 否则新旧向量不可比——这是 RAG-D03 §5 索引代际（generation）机制的由来。
 */
public interface EmbeddingPort {

    /**
     * 批量嵌入。
     *
     * @param texts 文本列表
     * @return 向量列表；顺序与输入一致
     */
    @NonNull List<float[]> embed(@NonNull List<String> texts);

    /** @return 模型标识与版本（写入索引元数据，用于代际管理）。 */
    @NonNull String modelVersion();

    /** @return 向量维度。 */
    int dimension();
}
