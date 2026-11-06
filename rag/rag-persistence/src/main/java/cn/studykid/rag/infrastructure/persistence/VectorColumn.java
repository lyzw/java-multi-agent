package cn.studykid.rag.infrastructure.persistence;

import java.util.Set;

/**
 * 向量列的物理形态：pgvector 的<b>类型</b>与<b>维度</b>。
 *
 * <p><b>为什么需要把它显式建模</b>：嵌入模型决定向量维度，而维度写死在列类型里
 * （{@code rag_chunk_embedding.embedding}）。此前代码把 {@code vector(1024)} 当常量，
 * 于是「换一个大维度模型」的表现是<b>写库时才炸</b>——索引流水线会逐条失败，
 * 而查询向量的维度不符也只会被 PG 拒绝，错误信息离根因很远。
 * 现在维度来自 {@code EmbeddingPort.dimension()}、类型来自配置，
 * 两端（写入 SQL 的 cast、查询 SQL 的 cast）都用同一个对象，
 * 并在<b>发请求给数据库之前</b>校验维度。
 *
 * <p><b>⚠️ 类型名走白名单</b>：cast 是要拼进 SQL 的，绝不接受任意字符串
 * （{@code rag.embedding.vector-type} 只能取 {@value #TYPE_VECTOR} / {@value #TYPE_HALFVEC}）。
 *
 * <p><b>维度上限</b>（pgvector 0.8.x，HNSW/IVFFlat 索引）：{@code vector} 2000 维、
 * {@code halfvec} 4000 维。超过上限仍可<b>存储</b>，但<b>建不了 ANN 索引</b>，
 * 稠密检索退化为全表精确扫描——语料规模一大就会成为瓶颈。
 */
public record VectorColumn(String type, int dimension) {

    /** 全精度向量类型。 */
    public static final String TYPE_VECTOR = "vector";

    /** 半精度向量类型（存储减半，HNSW 维度上限更高）。 */
    public static final String TYPE_HALFVEC = "halfvec";

    /** 允许拼进 SQL 的 cast 类型白名单。 */
    public static final Set<String> SUPPORTED_TYPES = Set.of(TYPE_VECTOR, TYPE_HALFVEC);

    /** 各类型在 HNSW/IVFFlat 索引下可承载的最大维度。 */
    public static final int MAX_INDEXABLE_DIMENSIONS_VECTOR = 2000;
    public static final int MAX_INDEXABLE_DIMENSIONS_HALFVEC = 4000;

    public VectorColumn {
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "向量列类型只能是 " + SUPPORTED_TYPES + " 之一，当前='" + type + "'");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量列维度必须为正数，当前=" + dimension);
        }
    }

    /** 该类型在当前配置下能否建成 ANN 索引。 */
    public boolean indexable() {
        return dimension <= maxIndexableDimensions();
    }

    /** 该类型的 ANN 索引维度上限。 */
    public int maxIndexableDimensions() {
        return TYPE_HALFVEC.equals(type) ? MAX_INDEXABLE_DIMENSIONS_HALFVEC
                : MAX_INDEXABLE_DIMENSIONS_VECTOR;
    }

    /**
     * 校验一条向量的维度与本列一致。
     *
     * <p>放在发往数据库之前：维度不符时给出「模型/配置/列三处必须同步改」的可读错误，
     * 而不是 PG 的 {@code expected N dimensions, not M}。
     */
    public void requireDimension(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("向量不能为空");
        }
        if (vector.length != dimension) {
            throw new IllegalStateException("""
                    向量维度与列 %s(%d) 不符：实际 %d 维。
                    三者必须同时改：嵌入模型、rag.embedding.dimension、
                    rag_chunk_embedding.embedding 的列类型（并全量重建索引）。"""
                    .formatted(type, dimension, vector.length));
        }
    }

    /** JDBC 占位符的 cast 片段，如 {@code ?::halfvec}。 */
    public String castPlaceholder() {
        return "?::" + type;
    }
}
