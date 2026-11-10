package cn.studykid.rag.management.core;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.core.SuspicionRules.ChunkFacts;

/**
 * 零疑点来源的「确认性抽检」样本选取（V1.0.2 S3 补充）。
 *
 * <p><b>⚠️ 它解决什么</b>：{@link SuspicionRules} 只标记「已知的高风险模式」。
 * 一本切分质量很好、完全没有命中任何规则的教材，其 {@code listSuspects} 返回空，
 * 抽检台便没有可裁决的片段、提交按钮不出现，于是
 * {@code rag_source.inspection_status} 永远停在 {@code NOT_REQUIRED}，
 * 而 {@link InspectionGuard} 只认 {@code PASSED} —— 结果是
 * <b>质量越好的来源越签不出 VERIFIED</b>。这是一个真实的产品死锁。
 *
 * <p><b>⚠️ 它不是「放行」而是「给人工看的材料」</b>：本类只从正常片段中
 * <b>均匀抽样</b>若干条，附上 {@code ROUTINE_CONFIRM} 成因交给人确认。
 * 抽检结论仍由人提交的 {@link InspectionRecord} 经
 * {@link InspectionRules#evaluate} 聚合，<b>不放宽 D04 答案区漏判单点否决</b>，
 * 也<b>不</b>降低「必须抽检至少一条」的底线——空集合依旧不通过。
 *
 * <p><b>⚠️ 为什么优先抽答案类片段</b>：D04 §6 的核心风险是「答案被误判成讲解
 * 而泄题」。确认性抽检若要真正兜住这条底线，就应让人看到答案类片段本身，
 * 而不是只抽正文。故样本按「答案类优先、其余按页均匀」排序后截取。
 */
public final class ConfirmationSampling {

    /** 默认确认性抽检样本数（足够覆盖一册的答案区，又不让人翻到疲劳）。 */
    public static final int DEFAULT_SAMPLE_SIZE = 20;

    private ConfirmationSampling() {
    }

    /**
     * 从片段事实中抽取确认性抽检样本。
     *
     * <p>⚠️ <b>纯函数</b>：不访问数据库、不依赖作用域，故可被穷举单测，
     * 也能在平台全局档与租户档共用同一套抽样口径。
     *
     * @param facts  来源下的片段事实（通常已按 document_id / page / char_start 排序）
     * @param maxSamples 最多返回的样本数（&lt;=0 时取 {@link #DEFAULT_SAMPLE_SIZE}）
     * @return 均匀抽样的正常片段（包装为 {@code ROUTINE_CONFIRM} 疑点）；
     *         输入为空时返回空列表（此时仍无法抽检，由调用方如实呈现）
     */
    @NonNull
    public static List<InspectionSuspicion> sample(@NonNull List<ChunkFacts> facts,
                                                   int maxSamples) {
        int cap = maxSamples <= 0 ? DEFAULT_SAMPLE_SIZE : maxSamples;
        if (facts == null || facts.isEmpty() || cap == 0) {
            return List.of();
        }

        // 1) 答案类优先：这些片段直接关系 D04「答案区漏判=0」的底线。
        // 2) 其余按页均匀：用等距步长从正文里取，避免只看到封面/目录。
        List<ChunkFacts> answerBearing = new ArrayList<>();
        List<ChunkFacts> others = new ArrayList<>();
        for (ChunkFacts f : facts) {
            if (SuspicionRules.isAnswerBearing(f.role())) {
                answerBearing.add(f);
            } else {
                others.add(f);
            }
        }

        List<ChunkFacts> picked = new ArrayList<>(Math.min(cap, facts.size()));
        picked.addAll(answerBearing.subList(0, Math.min(cap, answerBearing.size())));

        int remaining = cap - picked.size();
        if (remaining > 0 && !others.isEmpty()) {
            picked.addAll(evenlySpaced(others, remaining));
        }

        List<InspectionSuspicion> samples = new ArrayList<>(picked.size());
        for (ChunkFacts f : picked) {
            samples.add(InspectionSuspicion.of(
                    f.chunkId(), f.documentId(), f.role(), f.text(),
                    f.pageNumber(), f.sectionPath(), f.exerciseNo(),
                    InspectionSuspicion.Reason.ROUTINE_CONFIRM));
        }
        return samples;
    }

    /**
     * 从有序列表中等距取 n 条（含首尾），避免样本全部挤在开头。
     *
     * <p>n &gt;= size 时原样返回；n == 1 时取中间那条，比只取封面更有代表性。
     */
    @NonNull
    private static List<ChunkFacts> evenlySpaced(@NonNull List<ChunkFacts> ordered, int n) {
        int size = ordered.size();
        if (n >= size) {
            return new ArrayList<>(ordered);
        }
        List<ChunkFacts> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // n==1 → index = size/2；否则 0..size-1 等距。
            int index = (n == 1)
                    ? size / 2
                    : (int) Math.round((double) i * (size - 1) / (n - 1));
            out.add(ordered.get(Math.min(index, size - 1)));
        }
        return out;
    }
}
