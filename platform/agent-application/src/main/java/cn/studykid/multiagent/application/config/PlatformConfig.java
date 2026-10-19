package cn.studykid.multiagent.application.config;

import java.math.BigDecimal;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.core.config.ToolRiskLevel;

/**
 * 平台运行参数（DEC-9 / DEC-11 配置基线）。
 *
 * <p><b>配置键与保守初值</b>（P0-D00 §1.2 / §1.4）：
 * <pre>
 *   agent.run.max-cost-per-run          = 2.00 CNY
 *   agent.run.max-llm-calls             = 12
 *   agent.run.max-steps                 = 20
 *   agent.tenant.max-concurrent-runs    = 5
 *   agent.tool.risk-level-map           = {get_student_profile:READ, record_weakness:WRITE_PROFILE}
 *   agent.approval.required-by-risk     = {READ:NOT_REQUIRED, WRITE_PROFILE:OPTIONAL, WRITE_EXTERNAL:REQUIRED}
 *   agent.approval.ttl                  = 15m
 *   agent.approval.approver-scope       = PARENT_OR_TEACHER
 * </pre>
 *
 * <p><b>本类不直接持有可空配置值</b>，而是通过 {@link ConfigResolver} 解析，
 * 解析失败一律<b>拒绝</b>（fail-closed），不做「取默认值」的宽松放行
 * （P0-D03 §4 红线 15 / R-020）。
 *
 * <p>配置在 <b>Run 创建时</b>被快照，之后改配置只影响新 Run（R-021）。
 */
public final class PlatformConfig {

    private PlatformConfig() {
    }

    /** 单 Run 成本上限（元）。 */
    public static final String KEY_MAX_COST_PER_RUN = "agent.run.max-cost-per-run";

    /** 单 Run 模型调用上限。 */
    public static final String KEY_MAX_LLM_CALLS = "agent.run.max-llm-calls";

    /** 单 Run 最大步数。 */
    public static final String KEY_MAX_STEPS = "agent.run.max-steps";

    /** 租户并发 Run 上限。 */
    public static final String KEY_MAX_CONCURRENT_RUNS = "agent.tenant.max-concurrent-runs";

    /** 工具风险级别映射。 */
    public static final String KEY_TOOL_RISK_MAP = "agent.tool.risk-level-map";

    /** 按风险级别的审批要求。 */
    public static final String KEY_APPROVAL_BY_RISK = "agent.approval.required-by-risk";

    /** 审批有效期。 */
    public static final String KEY_APPROVAL_TTL = "agent.approval.ttl";

    /** 审批人范围。 */
    public static final String KEY_APPROVAL_SCOPE = "agent.approval.approver-scope";

    /** 试点租户数上限。 */
    public static final String KEY_PILOT_MAX_TENANTS = "deployment.pilot.max-tenants";

    public static final String RPO = "dr.rpo";
    public static final String RTO = "dr.rto";

    /** DEC-9 保守初值。 */
    public static final BigDecimal DEFAULT_MAX_COST_PER_RUN = new BigDecimal("2.00");
    public static final int DEFAULT_MAX_LLM_CALLS = 12;
    public static final int DEFAULT_MAX_STEPS = 20;
    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 5;
    public static final int DEFAULT_PILOT_MAX_TENANTS = 1;

    /**
     * fail-closed 配置解析器。
     *
     * <p>语义：配置<b>缺失或非法一律抛异常拒绝</b>，绝不静默使用默认值放行。
     * 这里的默认值仅用于「生成配置文件模板」与「文档标注」，不参与运行时放行。
     */
    public static final class Resolver {

        private Resolver() {
        }

        /** @throws ConfigResolutionException 配置缺失、为 null 或非正数 */
        public static @NonNull BigDecimal requirePositiveDecimal(@Nullable Object raw,
                                                                 @NonNull String key) {
            if (raw == null) {
                throw new ConfigResolutionException(key, null, "配置缺失");
            }
            if (raw instanceof BigDecimal b) {
                requirePositive(b, key);
                return b;
            }
            if (raw instanceof Number num) {
                BigDecimal b = new BigDecimal(num.toString());
                requirePositive(b, key);
                return b;
            }
            try {
                BigDecimal b = new BigDecimal(raw.toString());
                requirePositive(b, key);
                return b;
            } catch (NumberFormatException e) {
                throw new ConfigResolutionException(key, raw, "非数值：" + raw);
            }
        }

        public static int requirePositiveInt(@Nullable Object raw, @NonNull String key) {
            if (raw == null) {
                throw new ConfigResolutionException(key, null, "配置缺失");
            }
            if (raw instanceof Number num) {
                int v = num.intValue();
                requirePositive(v, key);
                return v;
            }
            try {
                int v = Integer.parseInt(raw.toString().trim());
                requirePositive(v, key);
                return v;
            } catch (NumberFormatException e) {
                throw new ConfigResolutionException(key, raw, "非整数：" + raw);
            }
        }

        private static void requirePositive(@NonNull BigDecimal v, @NonNull String key) {
            if (v.signum() <= 0) {
                throw new ConfigResolutionException(key, v, "必须为正数");
            }
        }

        private static void requirePositive(int v, @NonNull String key) {
            if (v <= 0) {
                throw new ConfigResolutionException(key, v, "必须为正整数");
            }
        }

        /**
         * 解析工具的审批要求。
         *
         * <p>取「工具自身默认」与「按风险级别配置」中<b>更严格者</b>，
         * 保证配置组合顺序不会导致结果变松（P0-D03 §4 红线 15）。
         *
         * @param toolName      工具名
         * @param riskMap       工具→风险级别映射（可含未登记项，按 WRITE_EXTERNAL 兜底）
         * @param approvalByRisk 风险级别→审批要求映射（可含未登记项，用级别默认值兜底）
         */
        public static @NonNull ApprovalRequirement resolveApproval(
                @NonNull String toolName,
                @NonNull Map<String, ToolRiskLevel> riskMap,
                @NonNull Map<ToolRiskLevel, ApprovalRequirement> approvalByRisk) {
            ToolRiskLevel level = ToolRiskLevel.resolve(toolName, riskMap);
            ApprovalRequirement configured = approvalByRisk.get(level);
            ApprovalRequirement effective =
                    configured != null ? configured : level.defaultApproval();
            return level.defaultApproval().maxWith(effective);
        }
    }
}
