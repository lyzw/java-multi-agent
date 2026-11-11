package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 把历史消息切成「可整轮丢弃单元」与「永不裁锚点」（M5-a / 《方案》§5.3 W2）。
 *
 * <p>⚠️ <b>与 {@code ContextBudgetTrimmer.splitRounds()} 行为等价、但算法内聚在此</b>：
 * 原方法是 {@code agent-worker} 的私有方法，依赖方向（ADR-011）禁止
 * {@code agent-context → agent-worker}。故在此按同一规则重实现，M7 接线时
 * 统一由本类替代，杜绝「父子两套裁剪口径漂移」。规则一致：
 * <ul>
 *   <li>一条 ASSISTANT + 其后连续若干 TOOL = 一轮，要么全留、要么全丢；</li>
 *   <li>{@link ModelMessage.Role#TOOL} 若前面没有 ASSISTANT（结构异常），自己成一轮，
 *       丢它不连累别人、留它不冒充「某意图的结果」。</li>
 * </ul>
 *
 * <p>⚠️ <b>USER 消息是 ANCHOR，永不裁</b>（RV-2 首轮任务锚）：「任务目标往往在早期，
 * 恰是被滑窗丢掉的部分」。用户纠正 / 约束 / 负向知识都在 USER 消息里，
 * 一旦被裁就是「不报错、只是答案悄悄跑偏」。只有 TURN 可被整轮丢弃。
 *
 * <p>⚠️ 只做切分与拼装，不做 token 计数 / notice 渲染（那是
 * {@code CompactionPipeline} 的职责）；本类无 IO / 时钟 / 租户上下文。
 */
public final class RoundSplitter {

    /** 段类型。 */
    public enum Kind {
        /** USER 消息：锚点，永不裁。 */
        ANCHOR,
        /** ASSISTANT + 连续 TOOL：可整轮丢弃的单元。 */
        TURN
    }

    /**
     * 一个上下文段。
     *
     * @param kind     段类型
     * @param messages 段内消息（保序、非空）
     * @param fromSeq  段内首条消息在原始序列中的下标（含）
     * @param toSeq    段内末条消息在原始序列中的下标（含）
     */
    public record RoundSegment(@NonNull Kind kind,
                               @NonNull List<ModelMessage> messages,
                               int fromSeq,
                               int toSeq) {
        public RoundSegment {
            messages = List.copyOf(messages);
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("段不能为空");
            }
        }

        /** 是否可被整轮丢弃。 */
        public boolean droppable() {
            return kind == Kind.TURN;
        }

        /** 可逆引用前缀（{@code source_message_ids} 用，指向原始 run_message）。 */
        public @NonNull List<String> sourceRefs() {
            List<String> refs = new ArrayList<>(toSeq - fromSeq + 1);
            for (int i = fromSeq; i <= toSeq; i++) {
                refs.add("run_message:seq=" + i);
            }
            return List.copyOf(refs);
        }
    }

    private RoundSplitter() {
    }

    /**
     * 按轮切分。首条消息恒为 ANCHOR（原始输入 / 任务锚）。
     *
     * <p>⚠️ 空序列返回空列表；调用方不得假设至少有一段。
     */
    public static @NonNull List<RoundSegment> split(@NonNull List<ModelMessage> messages) {
        List<RoundSegment> segments = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ModelMessage m = messages.get(i);
            if (m.role() == ModelMessage.Role.USER) {
                // USER 永单独成锚点，绝不并入任一可丢轮。
                segments.add(new RoundSegment(Kind.ANCHOR, List.of(m), i, i));
                continue;
            }
            if (m.role() == ModelMessage.Role.ASSISTANT) {
                List<ModelMessage> round = new ArrayList<>();
                round.add(m);
                int j = i + 1;
                while (j < messages.size() && messages.get(j).role() == ModelMessage.Role.TOOL) {
                    round.add(messages.get(j));
                    j++;
                }
                segments.add(new RoundSegment(Kind.TURN, List.copyOf(round), i, j - 1));
                i = j - 1; // 跳过已被并入本轮的 TOOL
            } else {
                // 孤儿 TOOL（前面没有 ASSISTANT）：自己成一可丢轮。
                segments.add(new RoundSegment(Kind.TURN, List.of(m), i, i));
            }
        }
        return List.copyOf(segments);
    }

    /** 把段拼回消息序列（保序、无损；用于渲染）。 */
    public static @NonNull List<ModelMessage> flatten(@NonNull List<RoundSegment> segments) {
        List<ModelMessage> out = new ArrayList<>();
        for (RoundSegment segment : segments) {
            out.addAll(segment.messages());
        }
        return List.copyOf(out);
    }
}
