package cn.studykid.multiagent.application.cap;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 审批绑定服务（W-PERM-P3-T1 · §5.3.1 审批绑定契约）。
 *
 * <p>生成与消费两侧共用本服务，保证：
 * <ol>
 *   <li><b>生成</b>：算出 {@code approvalScopeHash}（范围定义摘要）与
 *       {@code effectiveArgsHash}（有效参数摘要）；</li>
 *   <li><b>消费</b>：用<b>同一份范围定义</b>重新验证实际参数是否仍落在范围内，
 *       并核对两个哈希是否与生成时一致。</li>
 * </ol>
 *
 * <p>⚠️ <b>为什么两个哈希缺一不可</b>：
 * <ul>
 *   <li>只有范围哈希 → 参数可被替换（把「给 A 收款人」改成「给 B 收款人」，
 *       范围本身没变，哈希照样匹配）；</li>
 *   <li>只有参数哈希 → 范围语义无处复核，且「同一批参数在另一个工具下被复用」无法识别。</li>
 * </ul>
 *
 * <p>⚠️ <b>消费时必须重验范围</b>（§5.3.1 原文）：
 * 「审批创建时由范围定义生成；消费时用范围定义<b>重新验证</b>实际参数是否落入范围」。
 * 只比对哈希是不够的 —— 哈希证明「没变」，不证明「落在批准范围内」。
 *
 * <p>⚠️ <b>规范化依据随范围定义一起落库</b>：
 * 有效参数集、Schema 默认值、集合语义都存在范围定义里（{@link ApprovalScope}），
 * 授予侧与消费侧因此用<b>同一份</b>规范化选项 ——
 * 否则「审批绑定的参数」与「实际执行的参数」算出两个哈希，防重放形同虚设。
 */
public class ApprovalBindingService {

    /**
     * 审批绑定（§5.3.1 的两个哈希）。
     *
     * @param approvalScopeHash 范围定义摘要
     * @param effectiveArgsHash 有效参数摘要
     */
    public record Binding(@NonNull String approvalScopeHash,
                           @NonNull String effectiveArgsHash) {
    }

    /**
     * 校验结论。
     *
     * @param matched     是否完全匹配
     * @param reasonCode  不匹配时的原因码（匹配时为 {@link ReasonCode#SUCCESS}）
     * @param violated    被违反的约束（诊断用）
     */
    public record Verdict(boolean matched,
                          @NonNull ReasonCode reasonCode,
                          @NonNull Set<String> violated) {
    }

    /**
     * 生成绑定。
     *
     * @param scope     审批范围定义
     * @param arguments 实际参数
     * @return 绑定
     */
    public @NonNull Binding generate(@NonNull ApprovalScope scope,
                                     @NonNull Map<String, Object> arguments) {
        return new Binding(scope.hash(),
                ArgsNormalizer.hash(arguments, scope.normalizationOptions()));
    }

    /**
     * 消费时校验绑定。
     *
     * <p>⚠️ <b>顺序不可换</b>：先重验范围（语义边界），再核对参数哈希（防替换）。
     * 两者都失败时返回「范围不匹配」—— 范围是更根本的边界，
     * 参数哈希不合往往正是因为范围已被突破。
     *
     * @param scope     审批范围定义（消费方持有的同一份）
     * @param expected  生成时算出的绑定
     * @param arguments 实际参数
     * @return 校验结论
     */
    public @NonNull Verdict verify(@NonNull ApprovalScope scope,
                                   @NonNull Binding expected,
                                   @NonNull Map<String, Object> arguments) {
        Set<String> violated = scope.violatedConstraints(arguments);
        if (!violated.isEmpty()) {
            return new Verdict(false, ReasonCode.APPROVAL_SCOPE_MISMATCH, violated);
        }
        if (!scope.hash().equals(expected.approvalScopeHash())) {
            // ⚠️ 范围定义本身对不上（审批授予后范围被改）→ 同样按范围不匹配处理
            return new Verdict(false, ReasonCode.APPROVAL_SCOPE_MISMATCH, Set.of());
        }
        String actualArgsHash = ArgsNormalizer.hash(arguments, scope.normalizationOptions());
        if (!actualArgsHash.equals(expected.effectiveArgsHash())) {
            return new Verdict(false, ReasonCode.APPROVAL_SCOPE_MISMATCH, Set.of());
        }
        return new Verdict(true, ReasonCode.SUCCESS, Set.of());
    }

    /**
     * 从工具的输入 Schema 与元数据构造范围骨架。
     *
     * <p>⚠️ <b>不写死有效参数集</b>：{@code effectfulParams} 为 {@code null}
     * 表示「全部参数参与摘要」（§5.3.1「{@code effectfulParams} 或默认全部」）。
     *
     * @param toolId          目标工具
     * @param inputSchema     输入 Schema（可空）
     * @param effectfulParams 有效参数集（{@code null} = 全部）
     * @param setValuedParams 集合语义参数（{@code null} = 无）
     * @return 范围骨架（约束为空，消费侧再按审批记录补全）
     */
    public @NonNull ApprovalScope scopeSkeleton(@NonNull String toolId,
                                                 @Nullable String inputSchema,
                                                 @Nullable Set<String> effectfulParams,
                                                 @Nullable Set<String> setValuedParams) {
        return new ApprovalScope(toolId, Map.of(), effectfulParams,
                ArgsNormalizer.defaultsFromSchema(inputSchema), setValuedParams);
    }
}
