package cn.studykid.multiagent.core.cap;

/**
 * 决策日志覆盖率（08 §7 · W-PERM-P5-T7）。
 *
 * <p>⚠️ 08 §7 的两条审计指标在此变成<b>可执行的断言</b>：
 * <ul>
 *   <li>「四字段完整率 100%」→ {@link #incompleteCount()} 必须为 0；</li>
 *   <li>「非笼统 DENY 占比 100%」→ {@link #nonSpecificDenyCount()} 必须为 0。</li>
 * </ul>
 *
 * @param total              日志总行数
 * @param invocationCount    调用类（INVOCATION）行数
 * @param denyCount          DENY 行数
 * @param nonSpecificDenyCount 笼统 DENY 行数（DENY 但原因码无意义）
 * @param incompleteCount    四字段不完整的行数
 */
public record DecisionAuditCoverage(
        long total,
        long invocationCount,
        long denyCount,
        long nonSpecificDenyCount,
        long incompleteCount) {

    /**
     * @return 是否满足 08 §7 的两条审计指标
     */
    public boolean isComplete() {
        return incompleteCount == 0 && nonSpecificDenyCount == 0;
    }

    /**
     * @return 非笼统 DENY 占比（无 DENY 时视为 1.0）
     */
    public double denySpecificityRate() {
        if (denyCount == 0) {
            return 1.0;
        }
        return (double) (denyCount - nonSpecificDenyCount) / denyCount;
    }

    /**
     * @return 四字段完整率（无日志时视为 1.0）
     */
    public double completenessRate() {
        if (total == 0) {
            return 1.0;
        }
        return (double) (total - incompleteCount) / total;
    }
}
