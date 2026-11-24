package cn.studykid.rag.infrastructure.embedding;

import org.jspecify.annotations.NonNull;

/**
 * MRL（Matryoshka Representation Learning）前缀截断 + L2 重新归一化（D29 DEC-P2 / S1）。
 *
 * <p><b>为什么需要它</b>：Qwen3-Embedding-8B 原生输出 4096 维，支持 MRL 前缀截断到
 * 32～4096 的任意档位。截断到 1024 后，向量范数会被缩小（只保留了前 1/4 分量），
 * 而 pgvector 的 cosine 距离虽然对整体缩放不敏感，<b>但本项目的相关性分是
 * {@code 1 - cosine_distance}</b> 且与逐字召回共用同一门禁——更重要的是，
 * 截断后不重新归一化会让「向量范数随维度漂移」，使分数分布整体平移、不可比
 * （D27 §14.11 禁止事项 3、D29 DEC-P2 S1）。因此截断后<b>必须</b>重新做 L2 归一化。
 *
 * <p><b>纪律（不可协商）</b>：
 * <ul>
 *   <li>只取<b>前缀</b>，不重排、不抽样；MRL 的有效语义就在前 N 维。</li>
 *   <li>目标维度必须 {@code <=} 源维度。⛔ <b>禁止零填充伪造维度</b>
 *       （D27 §14.10/§14.11、D29 DEC-P2 S3）——把 1024 补零成 4096 是欺诈。</li>
 *   <li>零向量 / 非有限数一律拒绝，不做「除零兜底成单位向量」这种掩盖。</li>
 * </ul>
 *
 * <p>纯函数、无 I/O、无框架依赖，便于单测与撤点反证（去掉归一化即可观察到范数≠1）。
 */
public final class MrlProjector {

    /**
     * 判定退化向量的平方范数下界。
     * 小于该值视为「零向量 / 数值下溢」，拒绝归一化而不是放大噪声。
     */
    private static final double MIN_SQUARED_NORM = 1e-12d;

    private MrlProjector() {
    }

    /**
     * 取前 {@code targetDimension} 维并对前缀重新做 L2 归一化。
     *
     * @param source 源向量（原生维度，如 4096）
     * @param targetDimension 目标维度（如 1024），必须 {@code 0 < target <= source.length}
     * @return 长度为 {@code targetDimension}、L2 范数≈1 的新数组（不修改入参）
     * @throws IllegalArgumentException 目标维度越界（含「想放大维度」的零填充企图）
     * @throws IllegalStateException 源前缀为零向量或含非有限数
     */
    public static @NonNull float[] project(@NonNull float[] source, int targetDimension) {
        if (targetDimension <= 0 || targetDimension > source.length) {
            throw new IllegalArgumentException(
                    "MRL 目标维度必须在 (0, 源维度] 区间：target=" + targetDimension
                            + "，source=" + source.length
                            + "。禁止零填充伪造更高维度（D29 DEC-P2 S3）");
        }
        float[] prefix = new float[targetDimension];
        double sumSquared = 0.0d;
        for (int i = 0; i < targetDimension; i++) {
            float value = source[i];
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(
                        "MRL 截断遇到非有限分量（index=" + i + "），拒绝归一化");
            }
            prefix[i] = value;
            sumSquared += (double) value * value;
        }
        if (sumSquared <= MIN_SQUARED_NORM) {
            throw new IllegalStateException(
                    "MRL 截断得到零向量 / 退化向量（前 " + targetDimension
                            + " 维平方范数=" + sumSquared + "），拒绝归一化");
        }
        double norm = Math.sqrt(sumSquared);
        for (int i = 0; i < targetDimension; i++) {
            prefix[i] = (float) (prefix[i] / norm);
        }
        return prefix;
    }

    /**
     * 计算 L2 范数，供断言与自检使用（不做任何修改）。
     */
    public static double l2Norm(@NonNull float[] vector) {
        double sumSquared = 0.0d;
        for (float value : vector) {
            sumSquared += (double) value * value;
        }
        return Math.sqrt(sumSquared);
    }
}
