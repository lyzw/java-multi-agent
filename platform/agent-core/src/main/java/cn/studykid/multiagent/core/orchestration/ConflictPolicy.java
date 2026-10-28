package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 规则与模型候选冲突时的裁决（10 号设计 §4.2 的 {@code on_conflict}，§6 阶段④）。
 *
 * <p>⚠️ <b>冲突必须留下痕迹</b>：无论裁决结果是哪一侧，都要记
 * {@link Resolution#ROUTING_CONFLICT} 指标 —— 否则「规则与语义长期不一致」
 * 会被裁决策略永久掩盖，而那份不一致恰恰是配置缺陷的信号。
 *
 * <p>⚠️ 裁决只作用于<b>选择</b>，<b>不作用于准入</b>：
 * 两侧目标都要过拓扑包含、固定版本、逐跳授权与深度/次数/超时/预算
 * （延续「降级只作用于选择，不作用于准入」的既有纪律）。
 */
public enum ConflictPolicy {

    /** 规则优先（<b>默认</b>：确定性是规则引擎存在的唯一理由）。 */
    RULE_WINS,

    /** 采纳模型候选（规则的命中只作观测）。 */
    CANDIDATE_WINS;

    /**
     * 按策略文本解析；未声明时用默认值 {@link #RULE_WINS}。
     *
     * @param wireName 策略文本里的写法（可为 {@code null}）
     * @return 枚举
     * @throws IllegalArgumentException 提供了但词汇表之外
     */
    public static @NonNull ConflictPolicy from(@Nullable String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return RULE_WINS;
        }
        for (ConflictPolicy policy : values()) {
            if (policy.name().equals(wireName.trim())) {
                return policy;
            }
        }
        throw new IllegalArgumentException("未知的 on_conflict: " + wireName + "（只有 "
                + java.util.Arrays.stream(values()).map(Enum::name).toList() + "）");
    }
}
