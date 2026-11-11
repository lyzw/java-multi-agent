package cn.studykid.multiagent.application.evaluation;

import java.math.BigDecimal;
import java.time.Clock;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

import cn.studykid.multiagent.application.config.ModelPricingPolicy;
import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import tools.jackson.databind.ObjectMapper;

/**
 * 评估层装配（§13 第 3 项 · L2 骨架）。
 *
 * <p>⚠️ <b>默认 fail-closed</b>：两个开关默认都 false —— 判分源是
 * {@link NoReferenceAnswerSource}（恒 empty），且<b>不注册</b> {@link AttributionJudge}。
 * 此时 {@link L2AttributionEvaluator} 即使被调用也只是「未启用」，
 * 看板显示「未启用 / 样本不足」，<b>绝不</b>编造归因正确率。
 *
 * <p>✅ 落地但不自动启用：
 * <ul>
 *   <li>{@code agent.quality.eval.reference-source.enabled=true} ⇒
 *       切到 {@link LocalFileReferenceAnswerSource}（默认 classpath 空题库，
 *       {@code file:} 外部路径覆盖）；</li>
 *   <li>{@code agent.quality.eval.llm-judge.enabled=true} ⇒
 *       注册 {@link ModelAttributionJudge}，复用生产 {@code chatStreamGateway}
 *       与冻结的模型单价表；</li>
 *   <li>{@link EvaluationCostBudget} 始终注册（无网络 / DB 副作用），
 *       作为评估科目独立预算，绝不混进业务成本。</li>
 * </ul>
 *
 * <p>⚠️ 未来换 DB / HTTP 题库只需替换 {@link ReferenceAnswerSource} bean，
 * 判定器与编排层不改；多实例要硬封顶 judge 成本时替换 {@link EvaluationCostBudget}
 * 为共享实现即可。
 */
@Configuration
public class EvaluationConfiguration {

    /** 默认题库资源：刻意为空，避免「配了开关就有一堆假答案」。 */
    private static final String DEFAULT_REFERENCE_LOCATION =
            "classpath:agent-eval/reference-answers.json";

    /**
     * 判分源：<b>单 bean + 内部开关</b>，而不是两个同类型 bean 叠加条件注解 ——
     * 后者在条件求值顺序变化时可能出现「两个都注册」或「两个都不注册」，
     * 症状是启动失败或静默落回 NoReference，极难反查。
     *
     * <p>{@code @ConditionalOnMissingBean} 保留：业务侧若注入自定义题库源，本 bean 自动让位。
     */
    @Bean
    @ConditionalOnMissingBean(ReferenceAnswerSource.class)
    public ReferenceAnswerSource referenceAnswerSource(
            @Value("${agent.quality.eval.reference-source.enabled:false}") boolean enabled,
            @Value("${agent.quality.eval.reference-source.location:"
                    + DEFAULT_REFERENCE_LOCATION + "}") @NonNull String location) {
        if (!enabled) {
            return new NoReferenceAnswerSource();
        }
        return new LocalFileReferenceAnswerSource(
                new DefaultResourceLoader().getResource(location), new ObjectMapper());
    }

    /**
     * 评估科目独立日预算。默认 1 元/日/实例；0 表示「一分钱都不许花」→ judge 恒不调用。
     *
     * <p>⚠️ 用 {@link Clock#systemUTC()} 而不是注入 Clock Bean：预算按 UTC 自然日归零，
     * 不应受业务时钟 / 测试夹具影响；单测直接构造带固定 Clock 的实例即可。
     */
    @Bean
    public EvaluationCostBudget evaluationCostBudget(
            @Value("${agent.quality.eval.llm-judge.daily-cost-limit-yuan:1.00}")
            @NonNull String dailyLimitYuan) {
        return new EvaluationCostBudget(new BigDecimal(dailyLimitYuan.trim()), Clock.systemUTC());
    }

    /**
     * L2 判定器：⚠️ <b>只在明确开启时注册</b>。
     *
     * <p>不注册时 {@link L2AttributionEvaluator} 的 {@code @Nullable AttributionJudge}
     * 注入 null，L2 明确「无判定器」而不是拿一个 NoOp 冒充已接线。
     *
     * <p>⚠️ 这里复用的是生产 {@code chatStreamGateway}（MiniMax / 目录默认模型同一实例），
     * 并显式 {@code @Qualifier} 锁定 Bean —— 项目里同类型多 Bean 时由注入点决定，
     * 不给实现加 {@code @Primary}。
     *
     * <p>⚠️ judge 用<b>专用 PromptGuard</b>放宽标准解析上限（默认 4096 会截断长解析，
     * 让判定器在信息不全时硬猜；这里给 16000，且被截断仍会 fail-closed）。
     */
    @Bean
    @ConditionalOnProperty(name = "agent.quality.eval.llm-judge.enabled", havingValue = "true")
    @ConditionalOnMissingBean(AttributionJudge.class)
    public AttributionJudge attributionJudge(
            @Qualifier("chatStreamGateway") @NonNull ChatStreamGateway gateway,
            @NonNull ModelPricingPolicy pricing,
            @NonNull EvaluationCostBudget budget,
            @Value("${agent.quality.eval.llm-judge.model:" + ModelAttributionJudge.DEFAULT_MODEL + "}")
            @NonNull String modelId,
            @Value("${agent.quality.eval.llm-judge.temperature:0}") double temperature,
            @Value("${agent.quality.eval.llm-judge.max-tokens:256}") int maxTokens,
            @Value("${agent.quality.eval.llm-judge.timeout-seconds:10}") long timeoutSeconds,
            @Value("${agent.quality.eval.llm-judge.min-confidence:0.75}") double minConfidence) {
        PromptGuard judgeGuard = new PromptGuard(
                PromptGuard.DEFAULT_MAX_INPUT_CHARS,
                PromptGuard.DEFAULT_MAX_DEPTH,
                PromptGuard.DEFAULT_MAX_ELEMENTS,
                16_000);
        return new ModelAttributionJudge(
                gateway, pricing, judgeGuard, budget, new ObjectMapper(),
                modelId, temperature, maxTokens, java.time.Duration.ofSeconds(timeoutSeconds),
                minConfidence);
    }
}
