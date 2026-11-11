package cn.studykid.multiagent.context;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import cn.studykid.multiagent.context.RollingStateMerger.DeltaExtractor;
import cn.studykid.multiagent.context.RollingStateMerger.MergeResult;
import cn.studykid.multiagent.context.RollingStateMerger.SeqMessage;
import cn.studykid.multiagent.context.StructuredState.Artifact;
import cn.studykid.multiagent.context.StructuredState.Fact;
import cn.studykid.multiagent.context.StructuredState.ToolCall;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * M5-c 增量状态合并内核行为守卫（《方案》§4.3 [D8] / §5.3 / §5.4）。
 *
 * <p>⚠️ 本类只验证纯算法：抽取能力由 {@link DeltaExtractor} 注入，绝不碰 LLM / DB / 时钟。
 * 每个失败分支都必须「回落 = 不伪造状态、不推进游标」。
 */
class RollingStateMergerTest {

    private static SeqMessage m(long seq, String content) {
        return new SeqMessage(seq, ModelMessage.user(content));
    }

    @Test
    @DisplayName("✅ RV-28：只吃 seq > upToSeq 的增量；稀疏 seq 不当 list index")
    void onlyNewerSeqIsProcessed() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<ModelMessage>> seen = new AtomicReference<>();
        StructuredState old = new S().cursor(0, 10).policy("pv").decisions("已有决定").build();
        List<SeqMessage> delta = List.of(m(8, "旧8"), m(10, "旧10"),
                m(11, "新11"), m(100, "新100"), m(1000, "新1000"));

        MergeResult r = RollingStateMerger.merge(old, delta, "pv", (o, msgs) -> {
            calls.incrementAndGet();
            seen.set(msgs);
            return Optional.of(new S().decisions("新增决定").build());
        });

        assertThat(calls).hasValue(1);
        assertThat(seen.get()).extracting(ModelMessage::content)
                .as("⛔ 抽取器收到了已吸收的老轮次：会递归重摘要 + 重复计费")
                .containsExactly("新11", "新100", "新1000");
        assertThat(r.processedMessages()).isEqualTo(3);
        assertThat(r.skippedMessages()).isEqualTo(2);
        assertThat(r.advanced()).isTrue();
        assertThat(r.fallback()).isFalse();
        assertThat(r.fallbackReason()).isNull();
        assertThat(r.discardCheckpoint()).isFalse();
        assertThat(r.state().upToSeq()).as("⛔ 游标未推进到最大新 seq").isEqualTo(1000);
        assertThat(r.state().fromSeq()).isZero();
        assertThat(r.state().policyVersion()).isEqualTo("pv");
    }

    @Test
    @DisplayName("✅ RV-37：无增量绝不调用抽取器，零成本、零副作用，旧状态引用不变")
    void noDeltaNeverCallsExtractor() {
        AtomicInteger calls = new AtomicInteger();
        StructuredState old = new S().cursor(0, 10).policy("pv").decisions("已有决定").build();
        DeltaExtractor forbidden = (o, msgs) -> {
            calls.incrementAndGet();
            throw new AssertionError("⛔ 无增量时抽取器被调用了");
        };

        MergeResult r = RollingStateMerger.merge(old,
                List.of(m(1, "旧"), m(10, "旧")), "pv", forbidden);
        assertThat(calls).hasValue(0);
        assertThat(r.state()).isSameAs(old);
        assertThat(r.advanced()).isFalse();
        assertThat(r.fallback()).isFalse();
        assertThat(r.skippedMessages()).isEqualTo(2);
        assertThat(r.processedMessages()).isZero();

        MergeResult emptyDelta = RollingStateMerger.merge(old, List.of(), "pv", forbidden);
        assertThat(calls).hasValue(0);
        assertThat(emptyDelta.state()).isSameAs(old);
        assertThat(emptyDelta.skippedMessages()).isZero();
    }

    @Test
    @DisplayName("✅ RV-30：抽取 empty / 抛异常 / 返回 null 一律回落，绝不伪造状态")
    void extractorFailuresFailClosed() {
        StructuredState old = new S().cursor(0, 5).policy("pv").decisions("已有决定").build();
        List<SeqMessage> delta = List.of(m(6, "新内容"));
        List<DeltaExtractor> broken = List.of(
                (o, msgs) -> Optional.empty(),
                (o, msgs) -> {
                    throw new IllegalStateException("模型不可用");
                },
                (o, msgs) -> null);

        for (DeltaExtractor extractor : broken) {
            MergeResult r = RollingStateMerger.merge(old, delta, "pv", extractor);
            assertThat(r.state()).as("⛔ 抽取失败却推进/改写了 checkpoint").isSameAs(old);
            assertThat(r.advanced()).isFalse();
            assertThat(r.fallback()).isTrue();
            assertThat(r.fallbackReason()).isEqualTo("EXTRACTOR_FAILED");
            assertThat(r.discardCheckpoint()).isFalse();
            assertThat(r.processedMessages()).isZero();
        }
    }

    @Test
    @DisplayName("✅ RV-33：关键 span 被改写或跨消息拼接 → 整批 VERBATIM_VIOLATION")
    void verbatimViolationFailsClosed() {
        StructuredState old = new S().cursor(0, 5).policy("pv").decisions("已有决定").build();
        List<SeqMessage> one = List.of(m(6, "用户说：不要使用 A-17 方案，方案号 A-17。"));

        StructuredState rewritten = new S().constraints("不要使用 A-18 方案").build();
        MergeResult bad = RollingStateMerger.merge(old, one, "pv",
                (o, msgs) -> Optional.of(rewritten));
        assertThat(bad.state()).isSameAs(old);
        assertThat(bad.fallback()).isTrue();
        assertThat(bad.fallbackReason()).isEqualTo("VERBATIM_VIOLATION");
        assertThat(bad.advanced()).isFalse();
        assertThat(bad.processedMessages()).isEqualTo(1);

        // span 跨消息拼接也不算逐字命中（防假阳性）。
        StructuredState exact = new S().constraints("不要使用 A-17 方案").build();
        List<SeqMessage> split = List.of(m(6, "用户说：不要使用"), m(7, "A-17 方案"));
        MergeResult splitResult = RollingStateMerger.merge(old, split, "pv",
                (o, msgs) -> Optional.of(exact));
        assertThat(splitResult.fallbackReason()).isEqualTo("VERBATIM_VIOLATION");
        assertThat(splitResult.state()).isSameAs(old);

        // 逐字命中才允许提交。
        StructuredState verbatim = new S().constraints("不要使用 A-17 方案")
                .facts(new Fact("方案号", "A-17")).build();
        MergeResult ok = RollingStateMerger.merge(old, one, "pv",
                (o, msgs) -> Optional.of(verbatim));
        assertThat(ok.advanced()).isTrue();
        assertThat(ok.fallback()).isFalse();
        assertThat(ok.state().constraints()).containsExactly("不要使用 A-17 方案");
        assertThat(ok.state().facts()).containsExactly(new Fact("方案号", "A-17"));
    }

    @Test
    @DisplayName("✅ RV-34：策略版本不符 → 丢弃 checkpoint，绝不复用旧策略的摘要")
    void policyVersionMismatchDiscardsCheckpoint() {
        AtomicInteger calls = new AtomicInteger();
        StructuredState old = new S().cursor(0, 9).policy("v1").decisions("旧策略决定").build();
        List<SeqMessage> delta = List.of(m(10, "新轮"));

        MergeResult r = RollingStateMerger.merge(old, delta, "v2", (o, msgs) -> {
            calls.incrementAndGet();
            return Optional.of(new S().build());
        });
        assertThat(r.state()).isSameAs(old);
        assertThat(r.fallback()).isTrue();
        assertThat(r.fallbackReason()).isEqualTo("POLICY_VERSION_MISMATCH");
        assertThat(r.discardCheckpoint()).isTrue();
        assertThat(r.advanced()).isFalse();
        assertThat(r.processedMessages()).isZero();
        assertThat(r.skippedMessages()).isZero();
        assertThat(calls).hasValue(0);

        // 空状态尚未绑定策略指纹，允许用当前版本起步。
        MergeResult fresh = RollingStateMerger.merge(StructuredState.empty(), delta, "v2",
                (o, msgs) -> Optional.of(new S().build()));
        assertThat(fresh.advanced()).isTrue();
        assertThat(fresh.state().policyVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("✅ 确定性合并：旧值原序 + 新值去重追加；facts 排序；引用按 ref 去重且新值覆盖")
    void mergeIsDeterministicAndDedupes() {
        StructuredState old = new S().constraints("约束1", "约束2").decisions("决定1")
                .artifacts(new Artifact("r1", "kind", "旧描述"), new Artifact("r2", "kind", "保留"))
                .facts(new Fact("z", "旧Z"), new Fact("a", "旧A"))
                .toolCalls(new ToolCall("grade", "t1", "旧工具"))
                .cursor(3, 10).policy("pv").build();
        StructuredState fragment = new S().constraints("约束2", "约束3").decisions("决定2")
                .artifacts(new Artifact("r1", "kind", "新描述"), new Artifact("r3", "kind", "新增"))
                .facts(new Fact("z", "新Z"), new Fact("b", "新B"))
                .toolCalls(new ToolCall("grade", "t1", "新工具"), new ToolCall("search", "t2", "检索"))
                .build();
        // 关键 span 必须逐字出现在增量原文，否则会走 VERBATIM_VIOLATION 回落。
        List<SeqMessage> delta = List.of(m(11, "约束2 约束3 新Z 新B"));

        MergeResult r = RollingStateMerger.merge(old, delta, "pv",
                (o, msgs) -> Optional.of(fragment));
        assertThat(r.fallback()).isFalse();
        assertThat(r.state().constraints()).containsExactly("约束1", "约束2", "约束3");
        assertThat(r.state().decisions()).containsExactly("决定1", "决定2");
        assertThat(r.state().artifacts()).containsExactly(
                new Artifact("r1", "kind", "新描述"),
                new Artifact("r2", "kind", "保留"),
                new Artifact("r3", "kind", "新增"));
        assertThat(r.state().facts()).containsExactly(
                new Fact("a", "旧A"), new Fact("b", "新B"), new Fact("z", "新Z"));
        assertThat(r.state().toolCalls()).containsExactly(
                new ToolCall("grade", "t1", "新工具"), new ToolCall("search", "t2", "检索"));
        assertThat(r.state().fromSeq()).isEqualTo(3);
        assertThat(r.state().upToSeq()).isEqualTo(11);

        MergeResult again = RollingStateMerger.merge(old, delta, "pv",
                (o, msgs) -> Optional.of(fragment));
        assertThat(StructuredStateCodec.digest(again.state()))
                .as("⛔ 同输入产生了不同 digest：幂等键 / checkpoint 缓存会失效")
                .isEqualTo(StructuredStateCodec.digest(r.state()));
    }

    @Test
    @DisplayName("✅ 目标只在片段给出时覆盖；合并结果不可变")
    void goalAndImmutability() {
        StructuredState old = new S().goal("旧目标").cursor(0, 4).policy("pv").build();
        List<SeqMessage> delta = List.of(m(5, "新轮"));

        MergeResult keep = RollingStateMerger.merge(old, delta, "pv",
                (o, msgs) -> Optional.of(new S().build()));
        assertThat(keep.advanced()).isTrue();
        assertThat(keep.state().goal()).isEqualTo("旧目标");
        assertThat(keep.state().constraints()).isUnmodifiable();
        assertThat(keep.state().facts()).isUnmodifiable();
        assertThat(keep.state().artifacts()).isUnmodifiable();

        MergeResult change = RollingStateMerger.merge(old, delta, "pv",
                (o, msgs) -> Optional.of(new S().goal("新目标").build()));
        assertThat(change.state().goal()).isEqualTo("新目标");
    }

    @Test
    @DisplayName("✅ RV-32：fromSeq 单调不后退（摘要链不丢头），upToSeq 单调前移")
    void cursorMonotonic() {
        StructuredState old = new S().cursor(5, 10).policy("pv").build();
        MergeResult r = RollingStateMerger.merge(old, List.of(m(20, "新轮")), "pv",
                (o, msgs) -> Optional.of(new S().build()));
        assertThat(r.state().fromSeq()).as("⛔ fromSeq 前移了：摘要链头部被悄悄丢弃").isEqualTo(5);
        assertThat(r.state().upToSeq()).isEqualTo(20);

        MergeResult fresh = RollingStateMerger.merge(StructuredState.empty(),
                List.of(m(7, "新轮")), "pv", (o, msgs) -> Optional.of(new S().build()));
        assertThat(fresh.state().fromSeq()).isZero();
        assertThat(fresh.state().upToSeq()).isEqualTo(7);
    }

    /** 12 字段显式构造器 + copy：加字段时必须在 copy 里逐字段带过（铁律 #10）。 */
    private static final class S {
        private String goal = "";
        private List<String> constraints = List.of();
        private List<String> userCorrections = List.of();
        private List<String> decisions = List.of();
        private List<String> rejectedApproaches = List.of();
        private List<String> openItems = List.of();
        private List<Artifact> artifacts = List.of();
        private List<Fact> facts = List.of();
        private List<ToolCall> toolCalls = List.of();
        private long fromSeq;
        private long upToSeq = -1;
        private String policyVersion = "pv1";

        S goal(String v) { goal = v; return this; }
        S constraints(String... v) { constraints = List.of(v); return this; }
        S corrections(String... v) { userCorrections = List.of(v); return this; }
        S decisions(String... v) { decisions = List.of(v); return this; }
        S rejected(String... v) { rejectedApproaches = List.of(v); return this; }
        S open(String... v) { openItems = List.of(v); return this; }
        S artifacts(Artifact... v) { artifacts = List.of(v); return this; }
        S facts(Fact... v) { facts = List.of(v); return this; }
        S toolCalls(ToolCall... v) { toolCalls = List.of(v); return this; }
        S cursor(long from, long upTo) { fromSeq = from; upToSeq = upTo; return this; }
        S policy(String v) { policyVersion = v; return this; }

        StructuredState build() {
            return new StructuredState(goal, constraints, userCorrections, decisions,
                    rejectedApproaches, openItems, artifacts, facts, toolCalls,
                    fromSeq, upToSeq, policyVersion);
        }
    }
}
