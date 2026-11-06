package cn.studykid.multiagent.memory.port;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 文本嵌入端口（P2 · 记忆的语义召回通道）。
 *
 * <p><b>⚠️ 为什么平台自己定义一份，而不是复用 {@code rag-ports} 的同名接口</b>：
 * {@code platform} 与 {@code rag} 是<b>两个独立的 Maven 反应堆</b>
 * （{@code platform/pom.xml} 的 modules 里没有 rag，{@code grep rag platform/} 为 0）。
 * 为了共用一个三方法接口而把整棵 rag 依赖树拉进 platform，
 * 等于让"记忆"这个可选特性绑架平台的依赖面 ——
 * 而记忆在 {@code enabled=false} 时必须能<b>完全消失</b>。
 *
 * <p>代价是两份同形接口，因此必须有一条纪律守住它们不漂移：
 * <b>维度与模型版本由使用方显式声明</b>（{@link #dimension()} /
 * {@link #modelVersion()}），任何一侧的默认值改变都会让
 * {@code student_memory.embedding} 的列定义与之不符，进而在建表期暴露。
 *
 * <p><b>⚠️ 本端口在 P1 不被使用</b>：P1 是纯结构化召回（按强度排序），
 * 不需要嵌入。留出本接口是为了让 P2 的混合召回有一个明确的落点，
 * 而不是先把向量列塞进 {@code student_memory} 再想办法填它 ——
 * 那会得到一列<b>永远为 NULL</b> 的 embedding，且无人察觉。
 */
public interface EmbeddingPort {

    /**
     * 批量嵌入。
     *
     * @param texts 文本列表；空列表必须返回空列表
     * @return 向量列表，<b>顺序与输入一致</b>，且每个向量长度必须等于 {@link #dimension()}
     */
    @NonNull
    List<float[]> embed(@NonNull List<String> texts);

    /**
     * 模型标识与版本。
     *
     * <p>⚠️ 必须写进 {@code student_memory.embedding_model_version}：
     * 换模型后新旧向量<b>不可比</b>，混用会让相似度排序变成噪声。
     *
     * @return 版本串，如 {@code "local-deterministic-v1"}
     */
    @NonNull
    String modelVersion();

    /** @return 向量维度（必须与 {@code student_memory.embedding} 的 {@code vector(n)} 一致）。 */
    int dimension();
}
