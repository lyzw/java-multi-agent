package cn.studykid.rag.core.ingest.rules;

import java.util.regex.Pattern;

import cn.studykid.rag.core.chunk.ChunkRole;

/**
 * 角色分类规则（方案 §4 Z8 / 设计 §3.2）。
 *
 * <p><b>⚠️ 逐条移植自 {@code scripts/rag_import_pdf.py} 的 {@code classify()}</b>。
 *
 * <p><b>⚠️ 这个类的判定顺序是不可交换的，改序等于改漏洞</b>：
 * <ol>
 *   <li><b>附录</b>优先：词表页里也会出现「答案」字样（如「答案见附录」），
 *       先判答案会把整页词表误判成答案区；</li>
 *   <li><b>答案</b>次之：误判为答案的代价是<b>泄题</b>，误判为讲解的代价仅是「不够具体」，
 *       两类错误代价不对称，故宁可过判答案类（D04 §3.1）；</li>
 *   <li><b>习题</b>再之：需要 ≥3 个题眼词（Ask/Write/Complete…）才判习题，
 *       单个动词不足以说明这是题区；</li>
 *   <li><b>讲解</b>最后。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类只是「正文分类的兜底」</b>：生产管道的角色判定以
 * {@code IngestionPipeline.resolveRole}（基于版面事实 {@code inAnswerZone}）为准。
 * 本类用于离线对拍与解析器未提供版面事实时的退路。
 */
public final class RoleRules {

    private RoleRules() {
    }

    /** 习题信号词（英语教材体例 + 中文）。 */
    private static final Pattern EXERCISE = Pattern.compile(
            "\\b(Ask|Write|Complete|Choose|Fill|Tick|Listen|Match|Read and|Look and|Talk"
                    + "|Practise|Practice)\\b|练习|作业|想一想|试一试");

    /** 讲解信号词。 */
    private static final Pattern EXPLANATION = Pattern.compile(
            "\\b(Grammar Focus|Grammar|Structure|Function|Pronunciation)\\b|语法|句型|语音|知识");

    /** 答案信号词。 */
    private static final Pattern ANSWER = Pattern.compile(
            "\\bAnswers?( to| key)?\\b|参考答案|答案");

    /** 附录信号词。 */
    private static final Pattern APPENDIX = Pattern.compile(
            "\\bWord [Ll]ist|Vocabulary\\b|词汇表|单词表|索引");

    /** 封面/版权/编者页信号词。 */
    private static final Pattern BOILERPLATE = Pattern.compile(
            "主\\s*编|分册主编|责任编辑|特约审读|封面设计|版式设计|插图绘制"
                    + "|出版|发行|印刷|定价|书\\s*号|版权所有|统一书号");

    /**
     * 按信号词判定正文角色（脚本 {@code classify}）。
     *
     * @param text 片段文本
     * @return 角色；无可识别信号时返回 {@link ChunkRole#PAPER_BODY}
     */
    public static ChunkRole classify(String text) {
        if (text == null || text.isBlank()) {
            return ChunkRole.PAPER_BODY;
        }
        if (APPENDIX.matcher(text).find()) {
            return ChunkRole.APPENDIX;
        }
        if (ANSWER.matcher(text).find()) {
            return ChunkRole.EXERCISE_ANSWER;
        }
        // ⚠️ 要求 ≥3 个题眼词：单个 "Write" 可能出现在任何讲解段落里。
        long hits = EXERCISE.matcher(text).results().count();
        if (hits >= 3) {
            return ChunkRole.EXERCISE;
        }
        if (EXPLANATION.matcher(text).find()) {
            return ChunkRole.EXPLANATION;
        }
        return ChunkRole.PAPER_BODY;
    }

    /**
     * 判定是否为封面/版权/编者页（脚本 {@code BOILERPLATE_RE}）。
     *
     * <p>⚠️ 这类页每册位置不同（有的在扉页、有的在封底），
     * <b>只能按内容判而非按页号判</b>；混进语料会污染召回。
     *
     * @param text 页文本
     * @return 是噪声页返回 true
     */
    public static boolean isBoilerplatePage(String text) {
        return text != null && !text.isBlank() && BOILERPLATE.matcher(text).find();
    }
}
