package cn.studykid.multiagent.routing;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.OnError;

/**
 * 一条路由规则（10 号设计 §4.3）。
 *
 * <p>⚠️ <b>{@code ordinal} 由服务端按声明序重排</b>，不信任策略文本里的值：
 * 「谁先判」是<b>语义</b>而不是装饰 —— 允许配置者手写序号，就会出现
 * 「文本里的序号」与「实际执行序」两份事实，而 V5（遮蔽判定）恰好依赖后者。
 *
 * <p><b>不可变</b>：本对象同时被发布期守卫和运行期引擎读取，
 * 任何一方可变都会让「校验过的策略」与「执行的策略」不再是同一个对象。
 *
 * @param ordinal 执行序号（从 1 起，升序求值）
 * @param ruleId  可读标识，进决策日志与 trace
 * @param target  命中后委派的目标（守卫 V2 保证它 ∈ 父 {@code childAgentRefs}）
 * @param when    条件谓词
 * @param onError 求值失败处置（只有两值，见 {@link OnError}）
 * @param note    配置者备注（可空；<b>不进模型 prompt，也不进判定</b>）
 */
public record RuleSpec(int ordinal,
                       @NonNull String ruleId,
                       @NonNull String target,
                       @NonNull Predicate when,
                       @NonNull OnError onError,
                       @Nullable String note) {

    public RuleSpec {
        if (ordinal < 1) {
            throw new IllegalArgumentException("规则序号必须由服务端回填且从 1 起: " + ordinal);
        }
        if (ruleId.isBlank() || target.isBlank()) {
            throw new IllegalArgumentException("规则的 ruleId 与 target 不能为空");
        }
    }

    /**
     * 回填执行序号。
     *
     * @param value 服务端按声明序算出的序号
     * @return 带序号的规则
     */
    public @NonNull RuleSpec withOrdinal(int value) {
        return new RuleSpec(value, ruleId, target, when, onError, note);
    }

    /**
     * 谓词的规范化签名字符串（决策快照里的 {@code matchedRuleSha256} 由它派生）。
     *
     * <p>用签名而不是整段 JSON：JSON 的键序与空白会随编辑工具漂移，
     * 而「命中的是哪条规则」必须能在两份不同排版的策略文本之间对齐。
     */
    public @NonNull String conditionSignature() {
        return when.signature();
    }
}
