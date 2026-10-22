package cn.studykid.rag.infrastructure.grounding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.rag.core.citation.Citation;
import cn.studykid.rag.core.grounding.GroundingVerdict;
import cn.studykid.rag.ports.EntailmentPort;

/**
 * 本地词法蕴含占位实现（RAG-D02 §3.3 / RAG-D06 §4 · G3 占位）。
 *
 * <p><b>⚠️ 这不是 NLI 蕴含模型，也不是 cross-encoder 重排器。</b>
 * 它只用<b>字符 bigram + 拉丁整词</b>的词重叠（recall 取向）估算
 * 「声明是否被证据支撑」，目的是让 {@code GroundingService} 的 G3
 * 垂直切片在<b>无外部模型</b>下可端到端跑通（与
 * {@code LocalDeterministicEmbeddingPort} 的占位定位一致）。
 *
 * <p><b>已知缺陷（占位实现，不得用于生产）</b>：
 * <ul>
 *   <li>纯词汇重叠，无法理解同义改写与逻辑蕴含；
 *       一句被正确改写但用词不同的支撑句会被判为「未接地」；</li>
 *   <li>不做语义蕴含判定，因此<b>不能</b>作为输出可信闸门的真实依据；</li>
 *   <li>{@link #verifyExistence} 占位放行（返回 true）并告警——
 *       真实实现须查询语料库确认 chunkId 存在/版本匹配/引文为子串，
 *       真实安全闸门由 {@code GroundingService} 基于 retrieved 片段的
 *       {@code Citation#pointsTo} 过滤兜底；</li>
 *   <li>{@link #detectConflict} 占位返回 null（无冲突检测）。</li>
 * </ul>
 *
 * <p><b>推进 G3 的真实路径</b>（仍受 <b>A-008 硬阻断</b>「首期不做 RAG」约束，
 * 且须接入与生成模型<b>不同</b>的 NLI/判别式）：把本类替换为
 * 独立蕴含模型，并把判定结果纳入评测集回归。
 */
public class LocalLexicalEntailmentPort implements EntailmentPort {

    private static final Logger log = LoggerFactory.getLogger(LocalLexicalEntailmentPort.class);

    /** 占位阈值：与 {@code GroundingPolicy.productionDefault()} 的 0.75 对齐，仅作本地切片演示。 */
    private static final double LOCAL_GROUNDED_THRESHOLD = 0.75;

    @Override
    public double entailment(@NonNull String claim, @NonNull String evidence) {
        Set<String> claimTokens = tokens(claim);
        Set<String> evidenceTokens = tokens(evidence);
        if (claimTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(claimTokens);
        intersection.retainAll(evidenceTokens);
        // recall 取向：声明中有多少比例的关键 token 在证据中出现。
        return (double) intersection.size() / claimTokens.size();
    }

    @Override
    public @NonNull List<GroundingVerdict> judge(@NonNull List<String> claims,
                                                @NonNull Map<String, List<Citation>> candidates) {
        List<GroundingVerdict> verdicts = new ArrayList<>();
        for (String claim : claims) {
            List<Citation> supporting = candidates.getOrDefault(claim, List.of());
            String evidence = supporting.stream()
                    .map(Citation::verbatimQuote)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            double score = evidence.isBlank() ? 0.0 : entailment(claim, evidence);
            GroundingVerdict.Verdict verdict = score >= LOCAL_GROUNDED_THRESHOLD
                    ? GroundingVerdict.Verdict.GROUNDED
                    : (score > 0.0 ? GroundingVerdict.Verdict.PARTIALLY_GROUNDED
                                   : GroundingVerdict.Verdict.UNGROUNDED);
            verdicts.add(new GroundingVerdict(
                    verdict, claim, supporting,
                    evidence.isBlank() ? "无引用证据" : null, score));
        }
        return verdicts;
    }

    @Override
    public boolean verifyExistence(@NonNull List<Citation> citations) {
        // ⚠️ 占位实现：无法访问语料库做真实存在性校验（chunkId 存在？版本匹配？引文为子串？）。
        // dev 环境下保守地「放行」以便本地切片跑通，但明确告警此处未被真正校验。
        // 生产须替换为语料库查询；真实安全闸门由 GroundingService 基于 retrieved 的 pointsTo 过滤提供。
        if (!citations.isEmpty()) {
            log.warn("[entailment] 占位实现 verifyExistence 未做真实语料存在性校验（dev 放行），生产须替换");
        }
        return true;
    }

    @Override
    public @Nullable String detectConflict(@NonNull List<Citation> citations) {
        // 占位实现：无冲突检测。
        return null;
    }

    /**
     * 极简分词：剥离标点/空白后，拉丁数字整词保留小写，CJK 串取字符 unigram + bigram。
     * 与索引侧 {@code ChineseTokenizer} 的取向一致，但自包含以避免端口实现间的隐式耦合。
     */
    private static Set<String> tokens(@NonNull String s) {
        Set<String> out = new HashSet<>();
        StringBuilder run = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c) || isPunctuation(c)) {
                flushRun(out, run);
            } else {
                run.append(Character.toLowerCase(c));
            }
        }
        flushRun(out, run);
        return out;
    }

    private static void flushRun(Set<String> out, StringBuilder run) {
        if (run.length() == 0) {
            return;
        }
        String r = run.toString();
        run.setLength(0);
        if (r.matches("[a-z0-9]+")) {
            out.add(r);
            return;
        }
        // CJK（或含符号的）串：字符 unigram + bigram
        for (int i = 0; i < r.length(); i++) {
            out.add(r.substring(i, i + 1));
            if (i + 1 < r.length()) {
                out.add(r.substring(i, i + 2));
            }
        }
    }

    private static boolean isPunctuation(char c) {
        return c == '，' || c == '。' || c == '；' || c == '：' || c == '、' || c == '？'
                || c == '！' || c == ',' || c == '.' || c == ';' || c == ':' || c == '?'
                || c == '!' || c == '（' || c == '）' || c == '(' || c == ')' || c == '"'
                || c == '\'' || c == '“' || c == '”' || c == '‘' || c == '’';
    }
}
