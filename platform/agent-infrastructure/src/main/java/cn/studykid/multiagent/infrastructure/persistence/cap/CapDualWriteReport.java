package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.List;

/**
 * 双写一致性比对报告（P0-T7 · 06 §3.3 第 4 步「新旧模型只读比对」）。
 *
 * <p><b>报告的判定口径</b>（P0 退出门禁：「双写一致性比对在抽样数据上差异为 0
 * （或已登记并获批的差异清单）」）：
 * <ul>
 *   <li>{@link #missingInCap()} —— 旧模型有、新模型无：<b>真实漂移</b>，必须收敛；</li>
 *   <li>{@link #extraInCap()} —— 新模型有、旧模型无：<b>真实漂移</b>（多为孤儿行）；</li>
 *   <li>{@link #mismatched()} —— 两侧都有但内容不一致：<b>真实漂移</b>；</li>
 *   <li>{@link #rejected()} —— 无法投影（V1 硬边界，如 {@code inherits}）：
 *       <b>不是漂移</b>，是「需人工处理」的登记项（06 §3.3 第 2 步的迁移报告）。</li>
 * </ul>
 *
 * <p>⚠️ <b>比对范围只含「已发布」版本</b>：双写只在发布时发生，
 * 草稿版本本就没有新模型对应行。把草稿算作差异会产出大量假阳性，
 * 让报告淹没在噪声里，反而掩盖真正的漂移。
 *
 * @param legacyVersionCount   旧模型已发布版本行数
 * @param capDefCount          新模型版本行数（已发布）
 * @param draftSkippedCount    旧模型草稿版本数（不在比对范围内）
 * @param matched              两侧一致的行数
 * @param missingInCap         旧有新无
 * @param extraInCap           新有旧无
 * @param mismatched           内容不一致
 * @param rejected             无法投影（需人工处理）
 * @param legacySnapshotCount  旧模型执行快照行数
 * @param capabilitySnapshotCount 新模型能力快照行数
 */
public record CapDualWriteReport(
        int legacyVersionCount,
        int capDefCount,
        int draftSkippedCount,
        int matched,
        @org.jspecify.annotations.NonNull List<Difference> missingInCap,
        @org.jspecify.annotations.NonNull List<Difference> extraInCap,
        @org.jspecify.annotations.NonNull List<Difference> mismatched,
        @org.jspecify.annotations.NonNull List<Difference> rejected,
        int legacySnapshotCount,
        int capabilitySnapshotCount
) {

    public CapDualWriteReport {
        missingInCap = missingInCap == null ? List.of() : List.copyOf(missingInCap);
        extraInCap = extraInCap == null ? List.of() : List.copyOf(extraInCap);
        mismatched = mismatched == null ? List.of() : List.copyOf(mismatched);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    /**
     * @return 是否<b>无真实漂移</b>（拒绝项不计入漂移，但需登记）
     */
    public boolean isClean() {
        return missingInCap.isEmpty() && extraInCap.isEmpty() && mismatched.isEmpty();
    }

    /**
     * @return 真实漂移总数
     */
    public int driftCount() {
        return missingInCap.size() + extraInCap.size() + mismatched.size();
    }

    /**
     * @return 需要人工处理的拒绝项总数
     */
    public int rejectedCount() {
        return rejected.size();
    }

    /**
     * @return 旧模型有执行快照、但新模型无能力快照的数量（<b>P1 依赖项</b>）
     */
    public int capabilitySnapshotGap() {
        return Math.max(0, legacySnapshotCount - capabilitySnapshotCount);
    }

    /**
     * 一条差异。
     *
     * @param agentId  Agent 标识
     * @param version  版本号
     * @param field    出问题的字段；整体缺失时为 {@code "-"}
     * @param expected 期望值（旧模型投影结果）
     * @param actual   实际值（新模型存储值）
     * @param reason   说明
     */
    public record Difference(
            @org.jspecify.annotations.NonNull String agentId,
            int version,
            @org.jspecify.annotations.NonNull String field,
            String expected,
            String actual,
            @org.jspecify.annotations.NonNull String reason
    ) {
    }
}
