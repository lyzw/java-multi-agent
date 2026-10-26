package cn.studykid.multiagent.core.cap;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * Skill 两级描述（契约 {@code skill.schema.json · $defs.skillDescription}）。
 *
 * <p><b>两级、消费者不同、粒度也不同</b>：
 * <ul>
 *   <li><b>1级 {@link #purpose()}</b> —— 供路由/选型：这句会进入模型可见的
 *       所属 Tool 的 description（模型挑工具时首先看到的信号），
 *       必须一句话讲清「做什么、适用什么请求」；</li>
 *   <li><b>2级 {@link #usage()}</b> —— 供执行：Skill 被命中后注入上下文的
 *       分场景操作指引（{@code when → how}），
 *       写明调用哪个已声明工具、什么顺序、参数从哪来、结果如何汇报。</li>
 * </ul>
 *
 * <p>⚠️ <b>描述不是授权</b>：本对象只引导模型使用
 * {@code declaredTools} / {@code declaredMcp} 已声明的能力，
 * 授权真相只有声明列表与 {@code riskCeiling} 一份；
 * 描述与声明冲突时按声明执行（fail-closed）。
 *
 * <p>⚠️ <b>内容即行为</b>：两级内容均计入 {@code configFingerprint}
 * —— 「改描述 = 改内容」，发布时重算指纹。
 */
public record SkillDescription(
        @NonNull String purpose,
        @NonNull List<SkillUsage> usage) {

    /**
     * 构造时归一：{@code usage} 缺省为空集（最小 Skill 允许只有 purpose）。
     */
    public SkillDescription {
        usage = usage == null ? List.of() : List.copyOf(usage);
    }

    /**
     * 一条分场景操作指引：{@code when} 描述「什么情况」，
     * {@code how} 描述「用何种操作」。
     *
     * <p>⚠️ 写作纪律来自契约：条目间应互斥或有明确优先级，
     * 不得出现「其他情况都用这条」式兜底条目（兜底交给模型判断）。
     */
    public record SkillUsage(
            /**
             * 触发条件。输入条件应可被模型从对话/任务上下文中直接判断。
             */
            @NonNull String when,
            /**
             * 操作步骤。仅允许引导已声明能力；
             * 涉及外部写入（WRITE_PROFILE / WRITE_EXTERNAL）时必须写明确认与回报要求。
             */
            @NonNull String how) {
    }
}
