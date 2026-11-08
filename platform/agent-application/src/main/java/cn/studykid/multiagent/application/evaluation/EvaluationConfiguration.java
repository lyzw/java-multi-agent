package cn.studykid.multiagent.application.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评估层装配（§13 第 3 项）。
 *
 * <p>⚠️ <b>默认 fail-closed</b>：仅注册「无任何参考答案」的安全默认
 *   {@link NoReferenceAnswerSource}。一旦产品确认并装配了真正的
 *   {@link ReferenceAnswerSource}（如查题库的实现），本 bean 由
 *   {@link ConditionalOnMissingBean} 自动让位，无需改这里。
 *
 * <p>⚠️ {@link AttributionJudge} 刻意<b>不</b>在此注册：平台当前没有可供评估链路调用的
 *   LLM 客户端，且其 rubric / 成本口径待产品确认。缺失时 {@link L2AttributionEvaluator}
 *   构造注入 null，L2 自动保持「未启用」，不会用占位实现冒充已接线。
 */
@Configuration
public class EvaluationConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReferenceAnswerSource.class)
    public ReferenceAnswerSource referenceAnswerSource() {
        return new NoReferenceAnswerSource();
    }
}
