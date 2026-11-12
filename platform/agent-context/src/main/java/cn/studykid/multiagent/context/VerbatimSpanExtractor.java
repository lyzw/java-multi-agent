package cn.studykid.multiagent.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.context.RollingStateMerger.DeltaExtractor;
import cn.studykid.multiagent.context.StructuredState.Fact;
import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * <b>确定性逐字跨度抽取器</b>：把用户轮次里的约束 / 纠正 / 精确事实抽成
 * {@link StructuredState} 片段（S3-M8-b / ADR-013 D49）。
 *
 * <p>⚠️ <b>为什么不用 LLM</b>：D49 明确 S3 不启用 W3 真摘要；若此处调模型，
 * 既越了阶段边界，又把「压缩态内容」变成不可复现的模型输出，断点回放与
 * 幂等键都会漂移。本类只做**切句 + 关键字分类 + 正则取原样**，
 * 每个 span 都是原文的<b>逐字子串</b>，因此天然满足
 * {@link RollingStateMerger#verifyVerbatim} 的逐字校验，不会产生
 * {@code VERBATIM_VIOLATION}。
 *
 * <p>⚠️ <b>只读 USER 轮次</b>：assistant / tool 内容由模型或外部系统生成，
 * 若把它们抽进状态，等于让模型改写自己的记忆——一旦输出里有幻觉 UUID 或
 * 编造数值，下一轮会被当成「已确认事实」回灌。宁可少记，也不把不可信来源
 * 洗成状态字段。
 *
 * <p>⚠️ <b>抽不到就返回 empty</b>：没有约束 / 纠正 / 精确事实时返回
 * {@link Optional#empty()}，由 {@link RollingStateMerger} 按
 * {@code EXTRACTOR_FAILED} 回落、**不写 checkpoint**。绝不返回空壳状态冒充成功
 * （那会让"没有可压缩内容"和"压缩成功但没抽到"无法区分）。
 *
 * <p>纯算法：零 Spring / 零 JDBC / 零 IO / 零时钟 / 零随机。
 */
public final class VerbatimSpanExtractor implements DeltaExtractor {

    /** 单条 span 上限：超过即截到句内前 N 字符（仍是原文子串）。 */
    static final int MAX_SPAN_CHARS = 200;

    /** 单次抽取的 span 总上限，防止长会话把状态撑爆。 */
    static final int MAX_SPANS_PER_CALL = 32;

    /** 约束标记：出现即认为该句是用户显式约束（"不要…" / "必须…"）。 */
    private static final Pattern CONSTRAINT_MARKER =
            Pattern.compile("(必须|不要|不能|禁止|务必|只能|不得|请勿|别忘|记住)");

    /** 纠正标记：用户改口 / 否定前文的负向知识。 */
    private static final Pattern CORRECTION_MARKER =
            Pattern.compile("(不是|不对|错了|应该|改成|我说的是|纠正|其实)");

    /** UUID：精确标识，必须逐字保留。 */
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * 精确数值：≥3 位整数、小数、或带单位/百分号。
     *
     * <p>单 digit 太噪声（"第 3 小问"每轮都有），故要求可判定的"精确"特征。
     */
    private static final Pattern NUMBER = Pattern.compile(
            "-?\\d{3,}(?:\\.\\d+)?|-?\\d+\\.\\d+|\\d+(?:\\.\\d+)?\\s*(?:%|万|千|分钟|小时|天|秒)");

    /** 句内分界：中英文句号 / 问叹 / 换行 / 分号。 */
    private static final Pattern SENTENCE = Pattern.compile("[^。！？\\n；;]+[。！？]?");

    @Override
    public @NonNull Optional<StructuredState> extract(@NonNull StructuredState oldState,
                                                      @NonNull List<ModelMessage> newMessages) {
        List<String> constraints = new ArrayList<>();
        List<String> corrections = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        Set<String> seenSpans = new LinkedHashSet<>();
        Set<String> seenFactKeys = new LinkedHashSet<>();
        int budget = MAX_SPANS_PER_CALL;

        for (ModelMessage message : newMessages) {
            if (message.role() != ModelMessage.Role.USER || message.content().isEmpty()) {
                continue;
            }
            Matcher sentences = SENTENCE.matcher(message.content());
            while (sentences.find() && budget > 0) {
                String span = trimVerbatim(sentences.group());
                if (span.isEmpty()) {
                    continue;
                }
                boolean constraint = CONSTRAINT_MARKER.matcher(span).find();
                boolean correction = CORRECTION_MARKER.matcher(span).find();
                if (constraint && seenSpans.add("c:" + span)) {
                    constraints.add(span);
                    budget--;
                }
                if (correction && seenSpans.add("k:" + span)) {
                    corrections.add(span);
                    budget--;
                }
                budget -= collectFacts(span, facts, seenFactKeys, budget);
                if (budget <= 0) {
                    break;
                }
            }
            if (budget <= 0) {
                break;
            }
        }

        if (constraints.isEmpty() && corrections.isEmpty() && facts.isEmpty()) {
            return Optional.empty();
        }
        // 片段只带「新抽到的 span」；游标 / fromSeq / policyVersion 由 merger 统一裁决，
        // 此处一律留空/占位，避免抽取器成为第二个游标真相。
        return Optional.of(new StructuredState(
                "", List.copyOf(constraints), List.copyOf(corrections),
                List.of(), List.of(), List.of(), List.of(), List.copyOf(facts), List.of(),
                0, -1, ""));
    }

    /** 从一句话里抽 UUID 与精确数值；返回消耗的预算数。 */
    private static int collectFacts(@NonNull String span,
                                    @NonNull List<Fact> facts,
                                    @NonNull Set<String> seenFactKeys,
                                    int budget) {
        int used = 0;
        Matcher uuid = UUID.matcher(span);
        while (uuid.find() && used < budget) {
            String value = uuid.group();
            if (seenFactKeys.add("uuid:" + value)) {
                facts.add(new Fact("uuid:" + value, value));
                used++;
            }
        }
        Matcher number = NUMBER.matcher(span);
        while (number.find() && used < budget) {
            String value = number.group();
            if (seenFactKeys.add("num:" + value)) {
                facts.add(new Fact("num:" + value, value));
                used++;
            }
        }
        return used;
    }

    /**
     * 去掉句首尾空白，但仍保证结果是原文子串：
     * 只整体平移起止下标，绝不 trim 中间、绝不改写字符。
     */
    private static @NonNull String trimVerbatim(@NonNull String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && Character.isWhitespace(raw.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(raw.charAt(end - 1))) {
            end--;
        }
        if (end - start > MAX_SPAN_CHARS) {
            end = start + MAX_SPAN_CHARS;
        }
        return raw.substring(start, end);
    }
}
