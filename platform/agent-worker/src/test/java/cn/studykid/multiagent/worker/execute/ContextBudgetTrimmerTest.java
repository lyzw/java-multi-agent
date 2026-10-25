package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 上下文按 token 预算裁剪的单元测试（第二十六批 · C26-1 · A7）。
 *
 * <p>⚠️ <b>本类要证明的是「裁剪不能改变语义」</b>：
 * 裁剪是<b>必要的</b>（不裁会顶爆上下文窗口），但它也是<b>有代价的</b> ——
 * 裁掉的东西模型就看不到了。故必须证明三件事：
 * <ol>
 *   <li><b>不该裁的不裁</b>（没超预算 → 原样返回）；</li>
 *   <li><b>裁了不产生畸形上下文</b>（不留孤立 TOOL；原始输入永不被裁）；</li>
 *   <li><b>裁了必须留痕</b>（模型要知道「前面调过哪些工具」，
 *       否则它会<b>重复调用</b> → 重复副作用 = Sev-0）。</li>
 * </ol>
 *
 * <p>⚠️ <b>「看得见」与「不该给的不给」必须成对</b>：
 * 每条「裁掉了」旁边都有一条「不该裁的没裁」。
 */
class ContextBudgetTrimmerTest {

    private static ModelMessage user(String content) {
        return ModelMessage.user(content);
    }

    private static ModelMessage assistant(String content) {
        return ModelMessage.assistant(content);
    }

    private static ModelMessage tool(String toolName, String content) {
        return ModelMessage.toolResult(toolName, content);
    }

    /** 造一条「一轮」：模型意图 + 若干工具结果。 */
    private static List<ModelMessage> round(String intent, String toolName, String output) {
        return List.of(assistant(intent), tool(toolName, output));
    }

    // ── 估算口径 ────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 汉字按 1 字 1 token 估（偏保守：中文 token 密度远高于英文）")
    void chineseIsEstimatedConservatively() {
        assertThat(ContextBudgetTrimmer.estimateTokens("二次函数")).isEqualTo(4);
    }

    @Test
    @DisplayName("✅ 非汉字按 3 字符 1 token 估（偏保守：主流模型约 4 字符/token）")
    void asciiIsEstimatedConservatively() {
        // 10 个 ASCII 字符 → ceil(10/3) = 4
        assertThat(ContextBudgetTrimmer.estimateTokens("0123456789")).isEqualTo(4);
    }

    @Test
    @DisplayName("✅ 空文本估算为 0")
    void emptyTextCostsNothing() {
        assertThat(ContextBudgetTrimmer.estimateTokens("")).isZero();
        assertThat(ContextBudgetTrimmer.estimateTokens((String) null)).isZero();
    }

    // ── 不该裁的不裁 ────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 未超预算 → 原样返回，一字不动（裁剪不得变成「总是裁」）")
    void withinBudgetIsReturnedUntouched() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(user("原始输入"));
        messages.addAll(round("意图 1", "tool_a", "结果 1"));
        messages.addAll(round("意图 2", "tool_b", "结果 2"));

        ContextBudgetTrimmer.Trimmed trimmed = ContextBudgetTrimmer.trim(messages, 10_000);

        assertThat(trimmed.trimmed())
                .as("⛔ 未超预算却被裁剪：上下文被无谓地截断，模型看不到本该看到的东西")
                .isFalse();
        assertThat(trimmed.messages()).hasSize(messages.size());
        assertThat(trimmed.messages()).isEqualTo(messages);
        assertThat(trimmed.notice()).isNull();
        assertThat(trimmed.withinBudget()).isTrue();
    }

    @Test
    @DisplayName("✅ 空序列 → 不裁剪，不抛异常")
    void emptySequenceIsSafe() {
        ContextBudgetTrimmer.Trimmed trimmed = ContextBudgetTrimmer.trim(List.of(), 100);

        assertThat(trimmed.messages()).isEmpty();
        assertThat(trimmed.trimmed()).isFalse();
        assertThat(trimmed.withinBudget()).isTrue();
    }

    @Test
    @DisplayName("⛔ 预算非正 → 显式拒绝（不得静默当成「无限预算」）")
    void nonPositiveBudgetIsRejected() {
        assertThatThrownBy(() -> ContextBudgetTrimmer.trim(List.of(user("x")), 0))
                .as("⛔ 非正预算被静默接受：等于给了一个「不设防也能跑」的构造路径")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 裁剪的语义 ──────────────────────────────────────────────────

    @Test
    @DisplayName("✅ 超预算 → 丢弃最早的轮次，保留最近的（最近的上下文对当前决策最相关）")
    void oldestRoundsAreDroppedFirst() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(user("原始输入"));
        messages.addAll(round("意图 1", "tool_a", "很长的结果 1"));
        messages.addAll(round("意图 2", "tool_b", "很长的结果 2"));
        messages.addAll(round("意图 3", "tool_c", "很长的结果 3"));

        int budget = ContextBudgetTrimmer.estimateTokens(messages) / 2;
        ContextBudgetTrimmer.Trimmed trimmed = ContextBudgetTrimmer.trim(messages, budget);

        assertThat(trimmed.trimmed()).isTrue();
        assertThat(trimmed.droppedRounds()).isGreaterThan(0);
        assertThat(trimmed.messages().get(0).content())
                .as("⛔ 原始输入被裁掉了：裁掉它等于改了任务本身")
                .isEqualTo("原始输入");
        assertThat(trimmed.messages().stream().map(ModelMessage::content))
                .as("⛔ 被裁掉的早期轮次仍留在发给模型的内容里：裁剪没生效")
                .noneMatch(c -> c.equals("很长的结果 1"));
        assertThat(trimmed.messages().stream().map(ModelMessage::content))
                .as("⛔ 最近的轮次被裁掉了：保留的必须是最近的上下文")
                .contains("很长的结果 3");
    }

    @Test
    @DisplayName("✅ 绝不留下孤立的 TOOL 消息（每条 TOOL 前面必有对应的模型意图）")
    void noOrphanToolMessagesRemain() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(user("原始输入"));
        for (int i = 1; i <= 6; i++) {
            messages.addAll(round("意图 " + i, "tool_" + i, "结果 " + i + " ".repeat(200)));
        }

        ContextBudgetTrimmer.Trimmed trimmed =
                ContextBudgetTrimmer.trim(messages, 400);

        assertThat(trimmed.trimmed()).isTrue();
        boolean seenAssistant = false;
        for (ModelMessage m : trimmed.messages()) {
            if (m.role() == ModelMessage.Role.ASSISTANT) {
                seenAssistant = true;
                continue;
            }
            if (m.role() == ModelMessage.Role.TOOL) {
                assertThat(seenAssistant)
                        .as("⛔ 出现了孤立的 TOOL 消息：它没有对应的模型意图，"
                                + "对模型是无意义噪声，部分供应商还会直接报错")
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("✅ 裁剪必须留痕：说明里要写清「移除了几轮」与「期间调用过哪些工具」")
    void trimmingLeavesATrace() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(user("原始输入"));
        messages.addAll(round("意图 1", "write_record", "结果 1 " + "x".repeat(500)));
        messages.addAll(round("意图 2", "search_documents", "结果 2 " + "y".repeat(500)));
        messages.addAll(round("意图 3", "tool_c", "结果 3 " + "z".repeat(500)));

        ContextBudgetTrimmer.Trimmed trimmed =
                ContextBudgetTrimmer.trim(messages, 300);

        assertThat(trimmed.notice())
                .as("⛔ 裁剪没有留痕：模型会以为前面那些工具**根本没调用过**，"
                        + "从而重复调用 → 重复副作用（重复写库 / 重复扣款）= Sev-0")
                .isNotNull();
        assertThat(trimmed.notice())
                .as("⛔ 留痕里没有写明移除了几轮")
                .contains("轮");
        assertThat(trimmed.droppedTools())
                .as("⛔ 没有记录被裁掉轮次调用过的工具：模型无从知道自己已经调过什么")
                .isNotEmpty();
        assertThat(trimmed.notice())
                .as("⛔ 留痕里没有列出已调用过的工具名")
                .contains(String.join("、", trimmed.droppedTools().subList(0,
                        Math.min(2, trimmed.droppedTools().size()))));
    }

    @Test
    @DisplayName("✅ 裁剪后确实落在预算内（预算不是一个摆设）")
    void trimmedContextIsWithinBudget() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(user("原始输入"));
        for (int i = 1; i <= 8; i++) {
            messages.addAll(round("意图 " + i, "tool_" + i, "结果 " + i + " ".repeat(300)));
        }

        int budget = 500;
        ContextBudgetTrimmer.Trimmed trimmed = ContextBudgetTrimmer.trim(messages, budget);

        assertThat(trimmed.withinBudget())
                .as("⛔ 裁剪后仍超预算：裁剪没起到作用")
                .isTrue();
        assertThat(trimmed.estimatedTokens()).isLessThanOrEqualTo(budget);
    }

    @Test
    @DisplayName("✅ 原始输入本身超预算 → 保留输入并如实标记「超预算」（不得静默截断任务）")
    void oversizedHeadIsKeptAndFlagged() {
        List<ModelMessage> messages = List.of(user("很长的输入 " + "x".repeat(5000)));

        ContextBudgetTrimmer.Trimmed trimmed = ContextBudgetTrimmer.trim(messages, 100);

        assertThat(trimmed.messages().get(0).content())
                .as("⛔ 原始输入被截断了：截断用户输入等于悄悄改了任务本身")
                .isEqualTo("很长的输入 " + "x".repeat(5000));
        assertThat(trimmed.withinBudget())
                .as("⛔ 超预算状态被谎报为「在预算内」：运维会以为预算配得没问题")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 裁剪只改「发给模型的内容」，不改调用方手里的序列")
    void trimmingDoesNotMutateTheCallerSequence() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(user("原始输入"));
        messages.addAll(round("意图 1", "tool_a", "结果 1 " + "x".repeat(500)));
        messages.addAll(round("意图 2", "tool_b", "结果 2 " + "y".repeat(500)));
        int before = messages.size();

        ContextBudgetTrimmer.trim(messages, 100);

        assertThat(messages)
                .as("⛔ 裁剪把调用方的累积序列改了：裁一次就永久丢历史，后续轮次再也恢复不了")
                .hasSize(before);
    }
}
