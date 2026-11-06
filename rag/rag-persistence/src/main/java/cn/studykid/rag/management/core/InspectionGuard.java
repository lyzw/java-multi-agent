package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 抽检前置守卫（V1.0.2 §5 R2 / §3.2 / 设计 §2.2.4）。
 *
 * <p><b>⚠️ 本类只定义「能不能签发 VERIFIED」这一条额外条件，不改状态枚举</b>：
 * {@link VerificationTransition} 的 T-1~T-12 枚举与转移表<b>一律不动</b>
 * （方案 P4：改的是<b>迁移条件</b>，不是枚举）。这样既保留了既有的逐条断言测试，
 * 又补上了「未抽检不得放行」这条治理要求。
 *
 * <p><b>⚠️ 为什么抽检必须成为 VERIFIED 的硬前置</b>：
 * 抽检的核心指标是<b>答案区漏判=0</b>（漏判即泄题，D04 §6 单点否决）。
 * 若抽检只是「建议动作」，那么审核员在赶进度时会跳过它——
 * 而漏判一次答案区，就意味着标准答案被提前送到了还没作答的学生面前。
 * 把「抽检通过」变成迁移的前置条件，等于把这条底线从纪律变成机制。
 */
public final class InspectionGuard {

    private InspectionGuard() {
    }

    /**
     * 抽检状态（对应 {@code rag_source.inspection_status}）。
     *
     * <p>⚠️ 与 DDL 的三值严格一致：{@code NOT_REQUIRED / PASSED / FAILED}。
     */
    public enum InspectionStatus {
        /**
         * 免检。
         *
         * <p>⚠️ 哪些来源可以免检，方案标注为【待确认 T-8】——
         * 本枚举值存在是为了承载既有数据与「明确不需要抽检」的判定结果，
         * <b>不代表</b>系统会自动给出免检结论。默认入库值即为本状态，
         * 故未执行抽检的来源不会被误判为「已通过」。
         */
        NOT_REQUIRED,
        /** 抽检通过（答案区漏判=0 且其余指标达标）。 */
        PASSED,
        /** 抽检不通过（须重跑 job 后重新抽检）。 */
        FAILED;

        /**
         * 由字符串安全解析。
         *
         * @param raw 数据库字面量
         * @return 枚举
         * @throws IllegalArgumentException 未知值
         */
        public static InspectionStatus of(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("inspection_status 不能为空");
            }
            for (InspectionStatus v : values()) {
                if (v.name().equalsIgnoreCase(raw.trim())) {
                    return v;
                }
            }
            throw new IllegalArgumentException("未知的抽检状态：" + raw);
        }
    }

    /**
     * 校验是否允许签发 VERIFIED。
     *
     * <p><b>⚠️ 语义：仅 {@link InspectionStatus#PASSED} 放行</b>。
     * {@code NOT_REQUIRED} 也<b>不</b>放行——因为「免检」的判定条件尚未确认（T-8），
     * 在条件明确之前，默认态（NOT_REQUIRED）若被当作放行，
     * 等价于「所有新录入的来源都自动跳过抽检」，
     * 这恰恰是要防止的情形。等 T-8 确认后，再由上层的免检判定逻辑
     * 显式写入 PASSED（而非放宽本守卫）。
     *
     * @param status 当前抽检状态（可为 null，视为未抽检）
     * @return 允许签发返回 true
     */
    public static boolean allowsVerified(@Nullable InspectionStatus status) {
        return status == InspectionStatus.PASSED;
    }

    /**
     * 断言允许签发 VERIFIED，否则抛 409 语义的异常。
     *
     * <p>⚠️ 用 {@link IllegalStateException} 而非 HTTP 异常：
     * 领域层不感知传输协议；控制器把它翻译成 409（「这一步不被允许」），
     * 与既有非法状态转移的处置一致。
     *
     * @param status  抽检状态
     * @param sourceId 来源 ID（写进错误信息便于排障）
     */
    public static void requireInspectionPassed(@Nullable InspectionStatus status, @NonNull String sourceId) {
        if (!allowsVerified(status)) {
            throw new IllegalStateException(
                    "来源 " + sourceId + " 尚未通过人工抽检（当前抽检状态="
                            + (status == null ? "未记录" : status.name())
                            + "），不得签发 VERIFIED。请先在抽检台完成抽检且结论为 PASSED");
        }
    }
}
