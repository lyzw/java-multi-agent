package cn.studykid.rag.core.indexing;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 索引代际（RAG-D13 §3.5.2 蓝绿切换的领域模型）。
 *
 * <p><b>⚠️ 为什么需要「代际」这个概念</b>：
 * 换嵌入模型时新旧向量空间<b>不可比</b>（同一条文本在两个模型下的向量毫无关系）。
 * 若原地覆盖向量，重建期间检索只能用「半新半旧」的混合索引——
 * 那会静默产出错误的相似度，且没有任何报错。
 *
 * <p>代际让两套向量<b>并存</b>：旧代际继续服务（蓝），新代际在后台建好（绿）再原子切换。
 * 出问题还能切回去（回滚）。
 *
 * <p><b>⚠️ 状态机（严格单向）</b>：
 * <pre>
 *   BUILDING ──构建完成──▶ ACTIVE ──被新代际替换──▶ RETIRED
 *      │                     ▲                        │
 *      └──── 不可直接激活 ────┘                        └── 可回到 ACTIVE（回滚）
 * </pre>
 * 「回滚」= 把一个 RETIRED 代际重新置为 ACTIVE，同时把原 ACTIVE 置为 RETIRED。
 * <b>任何时刻每个租户至多一个 ACTIVE</b>——这是检索「只读一个代际」的前提。
 *
 * @param generation 代际号（从 1 开始递增）
 * @param embedModel 该代际使用的嵌入模型标识
 * @param dimension  向量维度
 * @param status     状态
 * @param indexedChunks 已写入向量行数（构建进度）
 * @param totalChunks   构建目标片段总数
 * @param note       备注
 */
public record IndexGeneration(
        int generation,
        @NonNull String embedModel,
        int dimension,
        @NonNull Status status,
        long indexedChunks,
        long totalChunks,
        @Nullable String note,
        @Nullable String activatedAt,
        @Nullable String retiredAt,
        @Nullable String createdAt,
        /**
         * 构建游标：上一个已处理的 chunk_id（RAG-D13 §3.5.2 异步分批）。
         *
         * <p><b>⚠️ 为什么需要持久化游标</b>：异步构建每轮只处理一批就释放，
         * 下一轮必须从上次位置继续。用 OFFSET 或 {@code indexedChunks} 当偏移都不行——
         * 构建期间片段被撤回/新增会让它们**整体错位，跳过部分片段**
         * （表现为"有些片段永远没有新代际的向量"，且无任何报错）。
         * 键集游标记住"扫到哪个 chunk_id"，不受增删影响。
         *
         * <p>null = 尚未开始。
         */
        @Nullable String buildCursor
) {

    /** 代际状态。 */
    public enum Status {
        /** 构建中：向量正在写入，**不参与检索**。 */
        BUILDING,
        /** 当前服务代际：检索只读它。 */
        ACTIVE,
        /** 已退役：不参与检索，但可回滚（重新激活）。 */
        RETIRED
    }

    /** 向后兼容构造（不含构建游标）。 */
    public IndexGeneration(int generation, @NonNull String embedModel, int dimension,
                           @NonNull Status status, long indexedChunks, long totalChunks,
                           @Nullable String note, @Nullable String activatedAt,
                           @Nullable String retiredAt, @Nullable String createdAt) {
        this(generation, embedModel, dimension, status, indexedChunks, totalChunks,
                note, activatedAt, retiredAt, createdAt, null);
    }

    public IndexGeneration {
        if (generation < 1) {
            throw new IllegalArgumentException("代际号必须 >= 1，收到：" + generation);
        }
        if (embedModel == null || embedModel.isBlank()) {
            throw new IllegalArgumentException("embedModel 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (indexedChunks < 0 || totalChunks < 0 || indexedChunks > totalChunks) {
            throw new IllegalArgumentException(
                    "进度不自洽：indexed=" + indexedChunks + " total=" + totalChunks);
        }
    }

    /** @return 构建进度百分比（0–100）；总数为 0 时返回 0（避免除零）。 */
    public int progressPercent() {
        if (totalChunks <= 0) {
            return 0;
        }
        return (int) Math.min(100, indexedChunks * 100 / totalChunks);
    }

    /**
     * @return 是否仍需继续构建（BUILDING 且未建完）。
     *
     * <p>⚠️ {@code totalChunks == 0} 视为"未开始"而非"已完成"：
     * 新建的代际 total 就是 0，若按"已建完"处理，worker 永远不会捡起它。
     */
    public boolean needsBuild() {
        return status == Status.BUILDING
                && (totalChunks == 0 || indexedChunks < totalChunks);
    }

    /** @return 是否可被检索使用。 */
    public boolean isRetrievable() {
        return status == Status.ACTIVE;
    }

    /**
     * @return 是否可切换到该代际。
     *
     * <p>⚠️ <b>规则：ACTIVE / RETIRED 恒可激活；BUILDING 只有「已构建完成」才可激活。</b>
     *
     * <p>为什么 BUILDING 不能无条件拒绝：异步构建完成后 {@code status} 仍是 BUILDING
     * （构建完成 ≠ 自动上线，是否切换由人决定），
     * 若按状态一刀切，**构建完成的代际将永远无法切换**
     * （2026-11-04 真实库实测：切换接口恒返回 409）。
     *
     * <p>为什么"建了一半"必须拒绝：那等于让检索去查一半是空向量的索引——
     * 表现为「有些片段永远召回不到」，且没有任何报错。
     * 判据是 {@link #buildFinished()}：由构建器在扫到末尾时写入。
     */
    public boolean isActivatable() {
        if (status == Status.ACTIVE || status == Status.RETIRED) {
            return true;
        }
        return status == Status.BUILDING && buildFinished();
    }

    /**
     * @return 构建是否已全部完成（由构建器在扫到末尾时标记）。
     *
     * <p>⚠️ 不能用 {@code indexedChunks >= totalChunks} 代替：
     * 计数相等有两种含义——"刚建完但还没扫到末尾确认" 与 "确实建完了"。
     * 构建器在收尾时把 {@code note} 写成含「构建已完成」的标记，以此区分。
     */
    public boolean buildFinished() {
        return note != null && note.contains("构建已完成");
    }
}
