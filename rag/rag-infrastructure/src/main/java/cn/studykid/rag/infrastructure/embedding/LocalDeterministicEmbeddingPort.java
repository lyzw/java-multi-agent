package cn.studykid.rag.infrastructure.embedding;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.EmbeddingPort;

/**
 * 嵌入端口的本地确定性占位实现（RAG-D02 §3.5 / RAG-D09 R2-1）。
 *
 * <p><b>⚠️ 诚实边界（务必阅读）</b>：本实现<b>不是</b>生产级嵌入模型。
 * 它以「字符 bigram 哈希 → 1024 维向量」生成<b>确定性但语义贫乏</b>的向量，
 * 仅用于：
 * <ul>
 *   <li>在<b>无 BGE-M3 部署</b>的环境下，让检索链路（稠密 + 词法 + RRF）跑通，
 *        验证<b>链路本身</b>、<b>版本/角色过滤</b>与 <b>RLS 隔离</b>；</li>
 *   <li>提供 RAG-D09 G2 条件⑤要求的「端到端检索跑通合成夹具」证据。</li>
 * </ul>
 * 生产<b>必须</b>替换为 BGE-M3（境内部署，NFR-RAG-008），其向量语义远优于此占位。
 * 本实现<b>绝不</b>用于「语义相关性判定」的生产结论——
 * 任何基于它的「相关性」都只是链路验证，不是质量结论。
 *
 * <p>确定性：相同输入恒得相同向量，使集成测试可复现（不引入随机性）。
 */
public class LocalDeterministicEmbeddingPort implements EmbeddingPort {

    /** 向量维度，与 V1 的 {@code vector(1024)} 一致。 */
    public static final int DIM = 1024;

    /** 模型标识：明确标注为本地占位，区别于真实模型标识。 */
    public static final String MODEL_VERSION = "local-deterministic-v1";

    @Override
    public @NonNull List<float[]> embed(@NonNull List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embedOne(text));
        }
        return result;
    }

    @Override
    public @NonNull String modelVersion() {
        return MODEL_VERSION;
    }

    @Override
    public int dimension() {
        return DIM;
    }

    /**
     * 单文本 → 确定性向量。
     *
     * <p>方法：遍历字符与 bigram，哈希映射到维度并累加权重，再做 L2 归一化。
     * 共享字符/bigram 越多的文本，向量余弦越接近——
     * 这足以在合成夹具下产生「相关文本得分更高」的可验证行为，
     * 但<b>不保证语义正确</b>（例如「二次函数」与「反二次函数」也会因共享字而相似）。
     */
    private float[] embedOne(String text) {
        float[] vec = new float[DIM];
        if (text == null || text.isBlank()) {
            return vec;
        }
        String norm = text.toLowerCase();
        for (int i = 0; i < norm.length(); i++) {
            int h1 = hashChar(norm.charAt(i));
            vec[h1 % DIM] += 1.0f;
            if (i + 1 < norm.length()) {
                int h2 = hashBigram(norm.charAt(i), norm.charAt(i + 1));
                vec[h2 % DIM] += 1.0f;
            }
        }
        double sum = 0.0;
        for (float v : vec) {
            sum += (double) v * v;
        }
        double norm2 = Math.sqrt(sum);
        if (norm2 > 0.0) {
            for (int i = 0; i < DIM; i++) {
                vec[i] = (float) (vec[i] / norm2);
            }
        }
        return vec;
    }

    private static int hashChar(char c) {
        return (c * 31) & Integer.MAX_VALUE;
    }

    private static int hashBigram(char a, char b) {
        return ((a * 31 + b) * 31) & Integer.MAX_VALUE;
    }
}
