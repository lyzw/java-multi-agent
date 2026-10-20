package cn.studykid.rag.core.knowledge;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 知识点（Knowledge Point）——教辅语料的<b>语义骨架</b>（RAG-D00 §2.3 决策 D-R05）。
 *
 * <p><b>⚠️ 为什么教辅场景必须有显式的知识点层</b>：
 * 碎片化是教辅语料的结构性特征，而非工程噪声：
 * <ul>
 *   <li>同一个知识点（「二次函数的顶点」）在教材正文里出现一次、
 *       在例题里一次、在课后习题里一次、在答案册里又一次；</li>
 *   <li>同一个知识点的<b>讲解</b>与<b>考查</b>被物理地放在两本书里；</li>
 *   <li>向量化切片会把这些片段打散成互不相关的向量，
 *       检索时召回「讲」的部分却漏掉「练」的部分（或反之）。</li>
 * </ul>
 * 纯向量检索无法解决「一次召回要同时覆盖讲解与练习」这种<b>集合完整性</b>需求。
 * 显式知识点层提供了这个能力：<b>先定位知识点，再沿知识点取其全部子片段</b>。
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>{@code canonicalName} 是<b>规范名</b>（如「二次函数顶点坐标」）；
 *       {@code aliases} 收录教材/教辅/题库中的<b>别称</b>
 *       （如「抛物线顶点」「顶点式」），用于查询归一化。</li>
 *   <li>{@code prerequisites} 表示<b>先修关系</b>，是「因知识缺口导致答错」的
 *       诊断依据——学生答错「顶点公式」常因不懂「配方」。</li>
 *   <li>{@code facets} 承载<b>考纲维度</b>（难度、认知层次、题型），
 *       让检索可以按教学意图过滤。</li>
 * </ul>
 */
public record KnowledgePoint(
        @NonNull String id,
        @NonNull String subject,
        @NonNull String grade,
        @NonNull String canonicalName,
        @NonNull List<String> aliases,
        @NonNull List<String> prerequisites,
        @NonNull List<Facet> facets
) {

    public KnowledgePoint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalArgumentException("canonicalName 不能为空");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        facets = facets == null ? List.of() : List.copyOf(facets);
    }

    /**
     * 判断给定文本是否<b>提到</b>本知识点。
     *
     * <p><b>⚠️ 方向不可颠倒：判定的是「文本包含名称」，不是「名称包含文本」</b>
     *
     * <p>本方法的两个调用方语义一致：
     * <ul>
     *   <li><b>入库侧</b>（{@code IngestionPipeline.matchKnowledgePoints}）：
     *       传入<b>片段正文</b>，问「这段话是不是在讲这个知识点」；</li>
     *   <li><b>检索侧</b>（RAG-D05 知识点定向检索）：传入<b>用户查询</b>，
     *       问「这个查询是不是在找这个知识点」。</li>
     * </ul>
     * 两者都是「<b>长文本里找短名称</b>」，
     * 因此判定式必须是 {@code 文本.contains(名称)}。
     *
     * <p>⚠️ <b>反向写法会造成静默零挂接</b>：
     * {@code 名称.contains(文本)} 要求<b>整段正文</b>是名称的子串，
     * 而正文必然长于名称 → <b>任何片段都挂不上知识点</b>。
     * 该缺陷不会报错、不会让构建变红，只会让
     * 「知识点补全」（RAG-D04 §4.3）永远无组可补——
     * <b>失败形态是「少」而不是「错」，在评测中极易被漏检</b>。
     *
     * <p>⚠️ <b>不做反向部分匹配</b>（{@code 名称.contains(文本)}）：
     * 短文本（如仅「顶点」两字）会命中「抛物线顶点」，
     * 造成碎片被误挂到不相干的知识点上。
     * <b>宁可漏挂不可误挂</b>：误挂会把别处的片段补进结果集（答非所问），
     * 漏挂只是少补（仍能检索到该片段本身）。
     *
     * <p><b>规范化必须双向覆盖</b>：规范名与<b>全部别名</b>都要参与判定
     * （去空格、全半角统一、大小写统一）。
     * 只匹配规范名会让「抛物线顶点」这类<b>教材/教辅/题库中的实际用词</b>
     * 直接失效，造成该知识点<b>系统性漏召回</b>。
     *
     * @param normalizedText 已归一化的待判定文本（片段正文或查询词）
     * @return 文本是否提到本知识点
     */
    public boolean matches(@NonNull String normalizedText) {
        if (normalizedText.isEmpty()) {
            return false;
        }
        if (normalizedText.contains(normalize(canonicalName))) {
            return true;
        }
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (!normalizedAlias.isEmpty() && normalizedText.contains(normalizedAlias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查询词归一化。
     *
     * <p>⚠️ 只做<b>保守</b>归一化（全半角、空白、大小写）。
     * <b>不</b>做同义词扩展——同义词必须由别称表显式登记，
     * 否则「无法」被替换成「不能」这类无控改写会让匹配变得不可解释、
     * 不可审计，而本项目的可验证性要求高于召回率。
     */
    public static @NonNull String normalize(@NonNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '　') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** @return 是否具有可独立检索的完整度（用于评测集构建时的可标注性检查）。 */
    public boolean isAnnotatable() {
        return !canonicalName.isBlank() && !subject.isBlank() && !grade.isBlank();
    }

    /** @return 供展示的规范名 + 别名。 */
    public @NonNull Optional<String> displayWithAliases() {
        return aliases.isEmpty()
                ? Optional.of(canonicalName)
                : Optional.of(canonicalName + "（" + String.join("、", aliases) + "）");
    }

    /** 考纲维度。 */
    public record Facet(@NonNull String dimension, @NonNull String value) {

        public Facet {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("dimension 不能为空");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("value 不能为空");
            }
        }
    }
}
