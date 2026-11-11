package cn.studykid.multiagent.context;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelMessage.Role;
import cn.studykid.multiagent.ports.model.ModelMessage.ToolCallEcho;
import cn.studykid.multiagent.context.RoundSplitter.RoundSegment;

/**
 * 按轮切分守卫（M5-a / 《方案》§5.3 W2，RV-2 首轮任务锚）。
 *
 * <p>⚠️ 断言锁定「USER 永不裁 + tool_call/tool_result 配对不破」，
 * 这是防「重复工具调用 → 重复副作用（Sev-0）」的结构保证。
 */
class RoundSplitterTest {

    private static ModelMessage user(String c) {
        return ModelMessage.user(c);
    }

    private static ModelMessage assistantToolCalls(String c, String callId, String tool) {
        return ModelMessage.assistantToolCalls(c,
                List.of(new ToolCallEcho(callId, tool, "{}")));
    }

    private static ModelMessage tool(String tool, String c, String callId) {
        return ModelMessage.toolResult(tool, c, callId);
    }

    @Test
    @DisplayName("✅ 首轮任务锚是 ANCHOR，永不裁（RV-2）")
    void firstUserTurnIsAnchor() {
        List<RoundSegment> segs = RoundSplitter.split(List.of(
                user("原始任务目标：解一元二次方程"),
                assistantToolCalls("调工具", "c1", "grade"),
                tool("grade", "结果", "c1")));

        assertThat(segs.get(0).kind()).isEqualTo(RoundSplitter.Kind.ANCHOR);
        assertThat(segs.get(0).droppable()).as("⛔ 首轮锚被标记为可裁：滑窗会丢任务目标（RV-2 反证）").isFalse();
        assertThat(segs.get(0).messages().get(0).content()).contains("任务目标");
    }

    @Test
    @DisplayName("✅ ASSISTANT + 连续 TOOL 成一轮，配对不破")
    void assistantAndToolsFormOneRound() {
        List<RoundSegment> segs = RoundSplitter.split(List.of(
                user("q"),
                assistantToolCalls("a1", "c1", "grade"),
                tool("grade", "r1", "c1"),
                tool("grade", "r2", "c1"),
                assistantToolCalls("a2", "c2", "fetch"),
                tool("fetch", "r3", "c2")));

        // user(ANCHOR) + round1(ASSISTANT+2 TOOL) + round2(ASSISTANT+1 TOOL)
        assertThat(segs).hasSize(3);
        assertThat(segs.get(1).kind()).isEqualTo(RoundSplitter.Kind.TURN);
        assertThat(segs.get(1).messages()).hasSize(3);
        assertThat(segs.get(2).messages()).hasSize(2);
        // 整轮丢弃时不会留下孤立 TOOL：每个 TURN 的首条必是 ASSISTANT。
        for (RoundSegment s : segs) {
            if (s.droppable()) {
                assertThat(s.messages().get(0).role())
                        .as("⛔ 可裁轮首条不是 ASSISTANT：丢弃会留孤立 TOOL，供应商 400")
                        .isEqualTo(Role.ASSISTANT);
            }
        }
    }

    @Test
    @DisplayName("✅ 中途 USER 纠正 / 约束也是 ANCHOR，不并入可丢轮")
    void midConversationUserIsAnchor() {
        List<RoundSegment> segs = RoundSplitter.split(List.of(
                user("q"),
                assistantToolCalls("a1", "c1", "grade"),
                tool("grade", "r1", "c1"),
                user("纠正：不要用方案 B，约束是必须逐字保留错误类型"),
                assistantToolCalls("a2", "c2", "fetch"),
                tool("fetch", "r2", "c2")));

        // seg0=ANCHOR(q), seg1=TURN(a1+r1), seg2=ANCHOR(纠正), seg3=TURN(a2+r2)
        assertThat(segs.get(2).kind()).isEqualTo(RoundSplitter.Kind.ANCHOR);
        assertThat(segs.get(2).messages().get(0).content()).contains("不要用方案 B");
    }

    @Test
    @DisplayName("✅ 孤儿 TOOL 单独成可丢轮，不冒充意图结果")
    void orphanToolIsItsOwnRound() {
        List<RoundSegment> segs = RoundSplitter.split(List.of(
                user("q"),
                tool("grade", "孤儿结果", "cx")));

        assertThat(segs).hasSize(2);
        assertThat(segs.get(1).kind()).isEqualTo(RoundSplitter.Kind.TURN);
        assertThat(segs.get(1).messages()).hasSize(1);
        assertThat(segs.get(1).messages().get(0).role()).isEqualTo(Role.TOOL);
    }

    @Test
    @DisplayName("✅ 拼装无损：split → flatten 逐条还原原序列")
    void flattenIsLossless() {
        List<ModelMessage> original = List.of(
                user("q"),
                assistantToolCalls("a1", "c1", "grade"),
                tool("grade", "r1", "c1"),
                user("纠正"),
                assistantToolCalls("a2", "c2", "fetch"),
                tool("fetch", "r2", "c2"));
        assertThat(RoundSplitter.flatten(RoundSplitter.split(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("✅ seq 区间与可逆引用正确（source_message_ids 可回取）")
    void seqRangeAndSourceRefs() {
        List<RoundSegment> segs = RoundSplitter.split(List.of(
                user("q"),
                assistantToolCalls("a1", "c1", "grade"),
                tool("grade", "r1", "c1")));

        assertThat(segs.get(1).fromSeq()).isEqualTo(1);
        assertThat(segs.get(1).toSeq()).isEqualTo(2);
        assertThat(segs.get(1).sourceRefs())
                .containsExactly("run_message:seq=1", "run_message:seq=2");
    }

    @Test
    @DisplayName("✅ 空序列 / 全 ANCHOR 序列不产生可丢轮")
    void edgeCases() {
        assertThat(RoundSplitter.split(List.of())).isEmpty();
        List<RoundSegment> allAnchors = RoundSplitter.split(List.of(user("q1"), user("q2")));
        assertThat(allAnchors).hasSize(2);
        assertThat(allAnchors).allMatch(s -> !s.droppable());
    }
}
