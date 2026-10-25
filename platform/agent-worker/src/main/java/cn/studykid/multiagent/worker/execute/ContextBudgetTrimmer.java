package cn.studykid.multiagent.worker.execute;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * 上下文<b>按 token 预算</b>的裁剪件（第二十六批 · A7）。
 *
 * <p>⚠️ <b>为什么需要它</b>：根循环与子执行器的消息序列都<b>随轮次线性增长</b> ——
 * 每轮追加「模型意图（ASSISTANT）+ 工具结果（TOOL）」。
 * 单条工具输出虽然有 8000 字符封顶（{@link ToolOutputLimiter}），
 * 但轮数一多（{@code maxSteps} 允许到 20），总量仍能顶爆上下文窗口 ——
 * 那时候模型看到的不是「结果」，而是「一堆噪声 + 供应商报错」，
 * 且报错长得像供应商故障，排查方向完全被带偏。
 *
 * <p>⚠️ <b>本类的 token 口径是「粗估」，不是真 tokenizer</b>（本批取舍）：
 * <ul>
 *   <li>中日韩汉字按 <b>1 字 1 token</b>；</li>
 *   <li>其余字符按 <b>3 字符 1 token</b>。</li>
 * </ul>
 * 两者都取<b>偏保守</b>的一侧：估算偏小会让实际 token 超出预算（顶爆窗口），
 * 估算偏大只是**早一点裁剪** —— 后者的代价远小于前者。
 * ⚠️ 真实 tokenizer 会随模型而变，本仓**没有**供应商 tokenizer 可用（D-002 未提供），
 * 故此处只能取一个与模型无关的保守下界。
 *
 * <p>⚠️ <b>裁剪只影响「发给模型的内容」，不影响我们自己的记账</b>：
 * 被裁掉的消息<b>不从累积序列里删除</b>，只是本轮**不发**给模型。
 * 否则裁一次就永久丢历史，后续轮次再也恢复不了。
 *
 * <p>⚠️ <b>按「轮次」整轮丢弃，绝不留下孤立的 TOOL 消息</b>：
 * 一条没有对应「模型意图」的 TOOL 消息对模型是无意义噪声，
 * 而部分供应商对「tool 结果没有对应 tool_call」直接报错。
 * 故一个「轮」= 一条 ASSISTANT + 其后连续的若干 TOOL，要么全留、要么全丢。
 *
 * <p>⚠️ <b>裁剪必须留痕（这是安全要求，不是 nicety）</b>：
 * 若不告诉模型「前面调过哪些工具」，它会以为那些工具**根本没调用过**，
 * 从而<b>重复调用</b> —— 那会产生<b>重复副作用</b>（重复写库 / 重复扣款 / 重复发消息），
 * 是 Sev-0 级风险。故裁剪时生成一条说明，告知：
 * ① 前面被移除了多少轮、约多少 token；② 期间<b>调用过哪些工具</b>。
 * 该说明走<b>系统提示</b>（可信指令载体），不混进消息序列 ——
 * 混进消息序列要么引入角色交替问题，要么把系统事实伪装成用户输入。
 */
public final class ContextBudgetTrimmer {

    /**
     * 默认上下文 token 预算。
     *
     * <p>⚠️ <b>本批自定的默认值，非评审冻结模型</b>：
     * 本仓**没有**「模型上下文窗口」这个事实的权威来源
     * （模型标识与参数来自 Agent 配置，但窗口大小没有对应字段），
     * 故取一个「足够宽松、但能拦住极端膨胀」的保守值。
     * 典型场景（{@code maxSteps} ≤ 5、单条工具输出 ≤ 8000 字符）不会触发裁剪；
     * 极端场景（多轮 + 大输出）才裁。
     */
    public static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 16_000;

    /**
     * 「非汉字部分」的字符数 ÷ 该值 = 估算 token 数。
     *
     * <p>⚠️ 取 3 而非 4：主流模型对英文约 4 字符/token，
     * 取 3 是<b>偏保守</b>的一侧（估算偏大 → 早裁剪）。
     */
    private static final int CHARS_PER_TOKEN = 3;

    /**
     * 裁剪结果。
     *
     * @param messages        裁剪后的消息序列（可直接发给模型）
     * @param notice          裁剪说明（{@code null} = 未裁剪）；走系统提示
     * @param droppedRounds   被移除的轮数
     * @param droppedTokens   被移除内容的估算 token 数
     * @param droppedTools    被移除轮次里调用过的工具名（去重、保序）
     * @param estimatedTokens 裁剪后**消息序列**的估算 token 数
     *                        （**不含裁剪说明** —— 说明走系统提示，不计入本预算）
     * @param withinBudget    裁剪后是否**确实**落在预算内
     */
    public record Trimmed(@NonNull List<ModelMessage> messages,
                          @Nullable String notice,
                          int droppedRounds,
                          int droppedTokens,
                          @NonNull List<String> droppedTools,
                          int estimatedTokens,
                          boolean withinBudget) {

        public Trimmed {
            messages = messages == null ? List.of() : List.copyOf(messages);
            droppedTools = droppedTools == null ? List.of() : List.copyOf(droppedTools);
        }

        /**
         * @return 是否发生了裁剪
         */
        public boolean trimmed() {
            return droppedRounds > 0;
        }
    }

    private ContextBudgetTrimmer() {
    }

    /**
     * 按默认预算裁剪。
     *
     * @param messages 消息序列
     * @return 裁剪结果
     */
    @NonNull
    public static Trimmed trim(@NonNull List<ModelMessage> messages) {
        return trim(messages, DEFAULT_CONTEXT_TOKEN_BUDGET);
    }

    /**
     * 按 token 预算裁剪消息序列。
     *
     * @param messages 消息序列（首条 = 原始输入，<b>永不被裁</b>）
     * @param budget   token 预算（必须为正数）
     * @return 裁剪结果
     */
    @NonNull
    public static Trimmed trim(@NonNull List<ModelMessage> messages, int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("预算必须为正数，收到: " + budget);
        }
        if (messages.isEmpty()) {
            return new Trimmed(List.of(), null, 0, 0, List.of(), 0, true);
        }
        int total = estimateTokens(messages);
        if (total <= budget) {
            return new Trimmed(List.copyOf(messages), null, 0, 0, List.of(), total, true);
        }

        ModelMessage head = messages.get(0);
        int headTokens = estimateTokens(head);
        List<List<ModelMessage>> rounds = splitRounds(messages);

        // ⚠️ keepFrom = 「被丢弃的最早几轮」的条数。
        //    保留的永远是**最近的**若干轮（rounds[keepFrom..]）——
        //    最近的上下文对当前决策最相关，早期的多是已经消化掉的中间步骤。
        //    keepFrom 越小 = 保留越多 = 越接近「全留」。
        Trimmed chosen = null;
        for (int candidate = 0; candidate <= rounds.size(); candidate++) {
            Trimmed attempt = build(messages, rounds, candidate, head, headTokens, budget);
            chosen = attempt;
            if (attempt.withinBudget()) {
                // ⚠️ candidate 越小保留越多 → 这是**保留轮数最多**的可行解，
                //    命中即返回（再往后的 candidate 只保留更少的轮次）。
                break;
            }
        }
        if (chosen == null) {
            // ⚠️ 理论上不可达（rounds.size() 的候选必然被求值过）；
            //    留一个显式兜底，免得将来改动让这里变成 NPE。
            chosen = build(messages, rounds, rounds.size(), head, headTokens, budget);
        }
        return chosen;
    }

    /**
     * 估算一段文本的 token 数（粗估，见类注释）。
     *
     * @param text 文本
     * @return 估算 token 数
     */
    public static int estimateTokens(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int codePoints = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            codePoints++;
            if (isWideScript(cp)) {
                cjk++;
            }
        }
        int others = codePoints - cjk;
        return cjk + (others + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * 估算一条消息的 token 数。
     *
     * @param message 消息
     * @return 估算 token 数
     */
    public static int estimateTokens(@NonNull ModelMessage message) {
        return estimateTokens(message.content());
    }

    /**
     * 估算一段消息序列的 token 数。
     *
     * @param messages 消息序列
     * @return 估算 token 数
     */
    public static int estimateTokens(@NonNull List<ModelMessage> messages) {
        int sum = 0;
        for (ModelMessage m : messages) {
            sum += estimateTokens(m);
        }
        return sum;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 构造某个 {@code keepFrom} 下的裁剪结果。
     *
     * @param rounds     按轮切分后的轮次
     * @param keepFrom   丢弃最早的多少轮
     * @param head       首条消息（原始输入）
     * @param headTokens 首条消息的估算 token 数
     * @param budget     token 预算
     * @return 裁剪结果
     */
    private static Trimmed build(List<ModelMessage> messages,
                                 List<List<ModelMessage>> rounds,
                                 int keepFrom,
                                 ModelMessage head,
                                 int headTokens,
                                 int budget) {
        List<ModelMessage> kept = new ArrayList<>();
        for (int i = keepFrom; i < rounds.size(); i++) {
            kept.addAll(rounds.get(i));
        }
        int droppedRounds = keepFrom;
        int droppedTokens = 0;
        Set<String> tools = new LinkedHashSet<>();
        for (int i = 0; i < keepFrom; i++) {
            for (ModelMessage m : rounds.get(i)) {
                droppedTokens += estimateTokens(m);
                if (m.role() == ModelMessage.Role.TOOL && m.toolName() != null) {
                    tools.add(m.toolName());
                }
            }
        }
        String notice = droppedRounds == 0 ? null : notice(droppedRounds, droppedTokens, tools);
        // ⚠️ **裁剪说明不计入本预算**：它走系统提示，而本预算的口径是
        //    「发给模型的消息序列」—— 系统提示与工具 Schema 都不计入。
        //    若把说明算进来，小预算下说明本身就会把预算吃光
        //    （实测：预算 15 token 时，一条 70 token 的说明把上下文压到只剩原始输入）。
        int used = headTokens + estimateTokens(kept);

        List<ModelMessage> out = new ArrayList<>(kept.size() + 1);
        out.add(head);
        out.addAll(kept);
        return new Trimmed(out, notice, droppedRounds, droppedTokens, List.copyOf(tools),
                used, used <= budget);
    }

    /**
     * 按「轮」切分：一条 ASSISTANT + 其后连续的若干 TOOL = 一轮。
     *
     * <p>⚠️ <b>孤儿 TOOL 单独成轮</b>：若某条 TOOL 前面没有 ASSISTANT
     * （结构异常），它自己就是一轮 —— 丢它时不会连累别的消息，
     * 留下时也不会冒充「某个意图的结果」。
     */
    private static List<List<ModelMessage>> splitRounds(List<ModelMessage> messages) {
        List<List<ModelMessage>> rounds = new ArrayList<>();
        List<ModelMessage> current = null;
        for (int i = 1; i < messages.size(); i++) {
            ModelMessage m = messages.get(i);
            if (m.role() == ModelMessage.Role.ASSISTANT) {
                current = new ArrayList<>();
                rounds.add(current);
            }
            if (current == null) {
                current = new ArrayList<>();
                rounds.add(current);
            }
            current.add(m);
        }
        return rounds;
    }

    /** 生成裁剪说明（走系统提示）。 */
    private static String notice(int droppedRounds, int droppedTokens, Set<String> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("（上下文预算裁剪：前 ").append(droppedRounds).append(" 轮工具交互已被移除")
                .append("（约 ").append(droppedTokens).append(" token）。");
        if (!tools.isEmpty()) {
            List<String> names = new ArrayList<>(tools);
            // ⚠️ 名单限长：说明本身走系统提示，不计入消息预算，
            //    但它仍会占用**真实的上下文窗口** —— 列 50 个工具名没有意义。
            int limit = 5;
            sb.append("期间已调用过：");
            for (int i = 0; i < names.size() && i < limit; i++) {
                if (i > 0) {
                    sb.append('、');
                }
                sb.append(names.get(i));
            }
            if (names.size() > limit) {
                sb.append(" 等 ").append(names.size()).append(" 个");
            }
            sb.append("。请勿重复调用上述工具。");
        }
        sb.append("移除是系统侧行为，任务本身未变。");
        return sb.toString();
    }

    /** 是否为「宽字符脚本」（按 1 字 1 token 估）。 */
    private static boolean isWideScript(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        };
    }
}
