package cn.studykid.multiagent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.RollingStateMerger.MergeResult;
import cn.studykid.multiagent.context.RollingStateMerger.SeqMessage;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * {@link VerbatimSpanExtractor} 行为测试（S3-M8-b / ADR-013 D49）。
 *
 * <p>锁死四件事：① 抽出的每个 span 都是原文**逐字子串**（否则 merger 的
 * `VERBATIM_VIOLATION` 会整批回落）；② 只读 USER 轮次，不把模型 / 工具输出洗成状态；
 * ③ 抽不到就返回 empty，绝不返回空壳状态冒充成功；④ 同输入恒等输出（幂等键可复用）。
 */
class VerbatimSpanExtractorTest {

    private final VerbatimSpanExtractor extractor = new VerbatimSpanExtractor();

    @Test
    @DisplayName("✅ 约束与纠正按原文逐字抽出，且每个 span 都是原文子串")
    void extractsConstraintsAndCorrectionsVerbatim() {
        String content = "你必须保留单位。之前那个 3.5 米不是最终答案，改成 3.75 米。";
        ModelMessage user = ModelMessage.user(content);

        StructuredState fragment = extractor.extract(StructuredState.empty(), List.of(user))
                .orElseThrow();

        assertThat(fragment.constraints()).containsExactly("你必须保留单位。");
        assertThat(fragment.userCorrections()).containsExactly("之前那个 3.5 米不是最终答案，改成 3.75 米。");
        for (String span : fragment.constraints()) {
            assertThat(content).contains(span);
        }
        for (String span : fragment.userCorrections()) {
            assertThat(content).contains(span);
        }
    }

    @Test
    @DisplayName("✅ UUID / 精确数值进 facts，key 稳定且重复值只留一条")
    void extractsPreciseFactsWithStableKeys() {
        String content = "单号 123e4567-e89b-12d3-a456-426614174000，误差 0.125 毫米，共 1280 次。";
        ModelMessage user = ModelMessage.user(content);

        StructuredState fragment = extractor.extract(StructuredState.empty(), List.of(user))
                .orElseThrow();

        assertThat(fragment.facts()).extracting(StructuredState.Fact::key)
                .contains("uuid:123e4567-e89b-12d3-a456-426614174000", "num:0.125", "num:1280");
        for (StructuredState.Fact fact : fragment.facts()) {
            assertThat(content).contains(fact.value());
            assertThat(fact.key()).endsWith(fact.value());
        }
    }

    @Test
    @DisplayName("✅ 只读 USER 轮次：assistant / tool 内容一律不抽（防模型幻觉洗成事实）")
    void ignoresNonUserMessages() {
        List<ModelMessage> messages = List.of(
                ModelMessage.assistant("你必须相信这条助手消息。单号 999e4567-e89b-12d3-a456-426614174999"),
                new ModelMessage(ModelMessage.Role.TOOL, "结果：不要重试", "calc", "call-1", List.of()),
                ModelMessage.user("普通的一句话，没有约束也没有精确值。"));

        assertThat(extractor.extract(StructuredState.empty(), messages)).isEmpty();
    }

    @Test
    @DisplayName("✅ 抽不到任何 span 时返回 empty：不制造空壳状态（否则会写空 checkpoint）")
    void returnsEmptyWhenNothingMatches() {
        assertThat(extractor.extract(StructuredState.empty(),
                List.of(ModelMessage.user("你好，今天天气怎么样")))).isEmpty();
        assertThat(extractor.extract(StructuredState.empty(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("✅ 同输入恒等输出：确定性 JSON 摘要稳定，checkpoint 幂等键可复用")
    void isDeterministic() {
        List<ModelMessage> messages = List.of(
                ModelMessage.user("必须用中文回答。数值 3.14159 要保留五位。"));

        StructuredState first = extractor.extract(StructuredState.empty(), messages).orElseThrow();
        StructuredState second = extractor.extract(StructuredState.empty(), messages).orElseThrow();

        assertThat(StructuredStateCodec.toJson(second))
                .isEqualTo(StructuredStateCodec.toJson(first));
        assertThat(StructuredStateCodec.digest(second))
                .isEqualTo(StructuredStateCodec.digest(first));
    }

    @Test
    @DisplayName("✅ 超长句截到 200 字符仍是原文子串，不产生改写")
    void longSentenceIsTruncatedVerbatim() {
        String content = "必须" + "细节".repeat(300);
        ModelMessage user = ModelMessage.user(content);

        StructuredState fragment = extractor.extract(StructuredState.empty(), List.of(user))
                .orElseThrow();

        assertThat(fragment.constraints()).hasSize(1);
        String span = fragment.constraints().get(0);
        assertThat(span.length()).isEqualTo(VerbatimSpanExtractor.MAX_SPAN_CHARS);
        assertThat(content).contains(span);
    }

    @Test
    @DisplayName("✅ 与 RollingStateMerger 真实串联：游标前移、逐字校验通过、无回落")
    void mergesIntoAdvancedStateWithoutFallback() {
        List<SeqMessage> delta = List.of(
                new SeqMessage(1, ModelMessage.user("你必须保留单位。")),
                new SeqMessage(2, ModelMessage.assistant("好的")));

        MergeResult result = RollingStateMerger.merge(StructuredState.empty(), delta,
                "cp_m8b", extractor);

        assertThat(result.fallback()).isFalse();
        assertThat(result.advanced()).isTrue();
        assertThat(result.processedMessages()).isEqualTo(2);
        assertThat(result.state().upToSeq()).isEqualTo(2);
        assertThat(result.state().policyVersion()).isEqualTo("cp_m8b");
        assertThat(result.state().constraints()).containsExactly("你必须保留单位。");
    }

    @Test
    @DisplayName("✅ 无可抽内容时不写状态：merger 回落 EXTRACTOR_FAILED，游标不推进")
    void emptyExtractionDoesNotAdvanceCursor() {
        List<SeqMessage> delta = List.of(new SeqMessage(1, ModelMessage.user("你好")));

        Optional<StructuredState> fragment = extractor.extract(StructuredState.empty(),
                List.of(ModelMessage.user("你好")));
        MergeResult result = RollingStateMerger.merge(StructuredState.empty(), delta,
                "cp_m8b", extractor);

        assertThat(fragment).isEmpty();
        assertThat(result.advanced()).isFalse();
        assertThat(result.fallback()).isTrue();
        assertThat(result.fallbackReason())
                .isEqualTo(RollingStateMerger.FALLBACK_EXTRACTOR_FAILED);
        assertThat(result.state().upToSeq()).isEqualTo(-1);
    }
}
