package cn.studykid.multiagent.core.compliance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台侧<b>输出内容安全层</b>（P1-A 修复）。
 *
 * <p><b>⚠️ 修复前的缺口</b>：全仓没有任何平台侧输出 moderation。
 * 唯一的「内容安全」是 LLM 供应商自带的模型侧过滤；
 * 而 {@code SENSITIVE_OUTPUT} 风险事件是<b>只观测不阻断</b>——
 * 答案里含身份证号时<b>照常下发给用户，只在后台记一笔</b>。
 * 对照 Azure Content Safety / OpenAI Moderation / NeMo output rails，
 * 主流护栏都有输出侧 moderation，本平台此处存在代差。
 *
 * <p><b>本层的判定是 fail-closed 的</b>：
 * <ul>
 *   <li>{@link Category#PII_LEAK} → 默认 {@link Action#REDACT}：
 *       命中片段换成占位符<b>再</b>下发。既不把可识别信息原样交给用户
 *       （等于「看见了、记录过、仍然发出去」），也不因一处 PII
 *       就把整道题讲废掉（可用性）。</li>
 *   <li>{@link Category#TOXICITY} / {@link Category#JAILBREAK_INSTRUCTION}
 *       → 默认 {@link Action#BLOCK}：命中即失败该 Run。
 *       面向<b>初中生</b>的产品，把自伤/暴力/施压话术原样下发不可接受。</li>
 *   <li>{@code null}/空输出 → {@link Action#ALLOW}（无内容可判定，
 *       由调用方决定是否算异常：<b>此处不得替调用方创造语义</b>）。</li>
 * </ul>
 *
 * <p><b>⚠️ 这是一份黑名单，不可能完备</b>（与该域既有组件同一纪律）：
 * 正则是<b>纵深防御的一层</b>，不是内容安全本身。生产应接入供应商
 * moderation 接口或自建分类器，并把结果经 {@link Category}
 * 归并到同一套 {@link Action} 决策上，避免再出现「多处各判一套」。
 *
 * <p><b>纯函数</b>：判定与 IO 分离，便于单测钉死每一类命中。
 */
public final class OutputModeration {

    private OutputModeration() {
    }

    /** 输出内容的风险类别。 */
    public enum Category {
        /** 无命中。 */
        NONE,
        /** 可识别信息泄漏（复用 {@link DataClassification.IdentifyingPattern} 既有合规正则）。 */
        PII_LEAK,
        /** 有害内容：自伤/暴力/侮辱/诱导。 */
        TOXICITY,
        /** 越狱话术：试图把输出当作指令去覆盖外部系统的行为。 */
        JAILBREAK_INSTRUCTION
    }

    /** 命中后的处置动作。 */
    public enum Action {
        /** 原样下发。 */
        ALLOW,
        /** 脱敏后下发（占位符替换命中片段）。 */
        REDACT,
        /** 阻断：调用方不得把原文交给下游。 */
        BLOCK
    }

    /**
     * 有害内容规则。
     *
     * <p>⚠️ 面向初中生：自伤/自杀倾向必须<b>阻断</b>——
     * 原样下发既不安全，也不符合未成年人保护要求。
     */
    private static final List<Rule> TOXICITY_RULES = List.of(
            new Rule("T-1 自伤/自杀", Pattern.compile(
                    "(自杀|自残|结束自己的生命|不想活了|想死|轻生)")),
            new Rule("T-2 暴力伤害", Pattern.compile(
                    "(打(死|伤)(你|他|她|他)|伤害(自己|同学|他人)|报复社会)")),
            new Rule("T-3 侮辱贬损", Pattern.compile(
                    "(你是(笨蛋|蠢货|废物|垃圾)|智障|弱智|去死)")));

    /**
     * 越狱话术规则。
     *
     * <p>⚠️ 输出侧与输入侧的判据不同：这里是模型<b>产出</b>了
     * 「请把以下文字当指令」「你现在处于开发者模式」这类内容，
     * 它会诱导下游（前端、工具、家长端）把不可信文本当指令执行。
     */
    private static final List<Rule> JAILBREAK_RULES = List.of(
            new Rule("J-1 输出伪装成指令", Pattern.compile(
                    "(?i)(execute|run|run the following)\\s+(as\\s+)?(command|指令)")),
            new Rule("J-2 声称已解锁", Pattern.compile(
                    "(?i)(developer\\s+mode|jailbreak|开发者模式|无限制模式|已被解锁)")),
            new Rule("J-3 诱导覆写上层指令", Pattern.compile(
                    "(?i)(ignore|忽略|disregard)\\s+(all\\s+)?(previous|prior|上述|以上)\\s+"
                            + "(instructions?|指令|规则)")));

    /** 每类命中的<b>默认</b>处置（fail-closed：有害类不得默认放行）。 */
    private static final Map<Category, Action> DEFAULT_ACTIONS =
            new EnumMap<>(Category.class);

    static {
        DEFAULT_ACTIONS.put(Category.NONE, Action.ALLOW);
        DEFAULT_ACTIONS.put(Category.PII_LEAK, Action.REDACT);
        DEFAULT_ACTIONS.put(Category.TOXICITY, Action.BLOCK);
        DEFAULT_ACTIONS.put(Category.JAILBREAK_INSTRUCTION, Action.BLOCK);
    }

    /** @return 该类别的默认处置动作（未知类别按 {@link Action#BLOCK}——fail-closed）。 */
    public static @NonNull Action defaultActionFor(@Nullable Category category) {
        if (category == null) {
            return Action.BLOCK;
        }
        Action action = DEFAULT_ACTIONS.get(category);
        return action == null ? Action.BLOCK : action;
    }

    /**
     * 按默认策略 moderation 一段输出。
     *
     * @param content 模型输出正文（可为 null）
     * @return 决策（含处置动作、脱敏后的正文、命中标签）
     */
    public static @NonNull Decision moderate(@Nullable String content) {
        return moderate(content, Map.of());
    }

    /**
     * 按自定义策略 moderation 一段输出。
     *
     * @param content  模型输出正文（可为 null）
     * @param overrides 类别 → 动作的覆盖；未覆盖的类别走默认动作
     * @return 决策
     */
    public static @NonNull Decision moderate(@Nullable String content,
                                             @NonNull Map<Category, Action> overrides) {
        Map<Category, Action> policy = new EnumMap<>(DEFAULT_ACTIONS);
        for (Map.Entry<Category, Action> e : overrides.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                policy.put(e.getKey(), e.getValue());
            }
        }

        if (content == null || content.isEmpty()) {
            return new Decision(Category.NONE, Action.ALLOW, content == null ? "" : content,
                    List.of(), List.of());
        }

        String redacted = content;
        Set<String> piiLabels = new LinkedHashSet<>();
        Set<String> hitLabels = new LinkedHashSet<>();

        // ① PII：按命中标签脱敏（保留句子结构，抹掉可识别片段）
        for (DataClassification.IdentifyingPattern p
                : DataClassification.IdentifyingPattern.values()) {
            // ⚠️ 复用监管部门同一份编译后的正则：检测与脱敏不得各写一份
            String masked = maskWith(redacted, p.pattern(), "[" + p.label() + "已脱敏]");
            if (!masked.equals(redacted)) {
                piiLabels.add(p.label());
                hitLabels.add("PII:" + p.label());
                redacted = masked;
            }
        }

        Category category = piiLabels.isEmpty() ? Category.NONE : Category.PII_LEAK;

        // ② 有害内容 / 越狱话术：优先级高于 PII（命中即整段阻断）
        for (Rule r : TOXICITY_RULES) {
            if (r.pattern().matcher(redacted).find()) {
                category = Category.TOXICITY;
                hitLabels.add(r.name());
            }
        }
        for (Rule r : JAILBREAK_RULES) {
            if (r.pattern().matcher(redacted).find()) {
                if (category == Category.NONE || category == Category.PII_LEAK) {
                    category = Category.JAILBREAK_INSTRUCTION;
                }
                hitLabels.add(r.name());
            }
        }

        Action action = defaultActionFor(category);
        Action decided = policy.getOrDefault(category, action);
        String delivered = decided == Action.BLOCK ? content : redacted;
        return new Decision(category, decided, delivered, List.copyOf(hitLabels),
                List.copyOf(piiLabels));
    }

    /**
     * moderation 决策。
     *
     * @param category   命中的最高类别（{@link Category#NONE} 表示无命中）
     * @param action     最终处置动作
     * @param content    <b>应下发</b>的正文：BLOCK 时回填原文
     *                   （调用方须自行丢弃，不得采用），REDACT 时为脱敏后的正文
     * @param labels     命中规则名（供观测/排障）
     * @param piiLabels  PII 命中的合规标签（如「身份证号」，用于 SENSITIVE_OUTPUT 事实写入）
     */
    public record Decision(
            @NonNull Category category,
            @NonNull Action action,
            @NonNull String content,
            @NonNull List<String> labels,
            @NonNull List<String> piiLabels) {

        /** @return 是否需要阻断该输出。 */
        public boolean blocked() {
            return action == Action.BLOCK;
        }
    }

    /** 把命中的片段统一替换为占位符。 */
    private static String maskWith(String text, Pattern pattern, String placeholder) {
        Matcher m = pattern.matcher(text);
        return m.replaceAll(Matcher.quoteReplacement(placeholder));
    }

    /** @return 全部规则名（供测试断言规则集未被悄悄删空）。 */
    public static @NonNull List<String> ruleNames() {
        List<String> names = new ArrayList<>();
        TOXICITY_RULES.forEach(r -> names.add(r.name()));
        JAILBREAK_RULES.forEach(r -> names.add(r.name()));
        return List.copyOf(names);
    }

    private record Rule(String name, Pattern pattern) {
    }
}
