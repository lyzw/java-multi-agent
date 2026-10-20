package cn.studykid.rag.core.chunk;

import org.jspecify.annotations.NonNull;

/**
 * 语料片段的角色（RAG-D00 §2.4 决策 D-R06）——<b>教辅最关键的切分维度</b>。
 *
 * <p><b>⚠️ 为什么「角色」比「内容」更重要</b>：
 * 教辅资料里，<b>同一段数学表述</b>在不同的角色下有不同的教学含义：
 * <ul>
 *   <li>出现在<b>正文</b>里 → 是「定义/定理」，可直接引用为依据；</li>
 *   <li>出现在<b>例题</b>里 → 是「示范解题过程」，引用它等于让学生抄步骤；</li>
 *   <li>出现在<b>习题</b>里 → 是「待作答的题目」，<b>答案不能提前给</b>；</li>
 *   <li>出现在<b>答案册</b>里 → 是「标准答案」，<b>引用它等于泄题</b>。</li>
 * </ul>
 * 若只按「文本相似度」区分，模型会从答案册取回答案并直接输出——
 * 对「这道题怎么做」的请求这是正确答案，对「给我讲讲这个知识点」
 * 的请求这是<b>教学事故</b>（泄题）。
 *
 * <p><b>因此角色必须是结构化字段并参与检索过滤</b>，
 * 而不是靠 prompt 里的自然语言约束（「不要泄题」在长上下文中不可靠）。
 *
 * <p><b>不可妥协的规则</b>：
 * <ol>
 *   <li>{@link #EXERCISE_ANSWER} 与 {@link #EXERCISE_HINT} 片段
 *       在「讲解模式」下<b>必须被排除在检索结果之外</b>，
 *       而不是召回后再让模型自行决定不说。</li>
 *   <li>{@link #EXERCISE}（题目本身）在任何模式下都可检索——
 *       学生要看题。</li>
 *   <li>角色判定基于<b>版面结构</b>（标题样式、题号模式、答案区标识），
 *       不基于模型判断（模型会把「讲评」误判为「答案」）。</li>
 * </ol>
 */
public enum ChunkRole {

    /** 教材/教辅正文：定义、定理、概念讲解。 */
    EXPLANATION,

    /** 例题及其<b>正文</b>（不含答案区）。 */
    EXAMPLE,

    /** 例题的<b>标准解答</b>（在答案册或书末答案区）。 */
    EXAMPLE_SOLUTION,

    /** 习题<b>题干</b>（含选项、图注）。 */
    EXERCISE,

    /** 习题<b>答案</b>——教学敏感内容。 */
    EXERCISE_ANSWER,

    /**
     * 习题<b>提示/思路引导</b>（不含最终答案）。
     *
     * <p>⚠️ 这是「讲解模式」的<b>唯一可接受的答案类内容</b>：
     * 它给出解题方向而不泄露结果。
     */
    EXERCISE_HINT,

    /** 学术论文正文。 */
    PAPER_BODY,

    /** 学术论文的方法/实验描述。 */
    PAPER_METHOD,

    /** 术语表、公式表、附录、参考文献等结构化附录。 */
    APPENDIX,

    /** 目录、页眉页脚、版权页等<b>无教学价值</b>的版面噪声。 */
    BOILERPLATE;

    /**
     * @return 该角色是否属于「答案类」敏感内容。
     *
     * <p>用于检索期过滤：讲解模式下答案类内容必须被排除。
     */
    public boolean isAnswerBearing() {
        return this == EXAMPLE_SOLUTION || this == EXERCISE_ANSWER;
    }

    /**
     * @return 该角色是否<b>可作为教学依据直接引用</b>。
     *
     * <p>⚠️ {@link #EXERCISE_ANSWER} <b>不可</b>作为依据被引用到讲解型回答里；
     * 它只在「核对/批改」场景下作为核对基准存在。
     */
    public boolean citableAsExplanation() {
        return this == EXPLANATION || this == APPENDIX
                || this == PAPER_BODY || this == PAPER_METHOD;
    }

    /**
     * @return 是否为纯版面噪声，入库时直接丢弃。
     */
    public boolean isNoise() {
        return this == BOILERPLATE;
    }
}
