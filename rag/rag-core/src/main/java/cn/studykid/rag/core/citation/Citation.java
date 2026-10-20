package cn.studykid.rag.core.citation;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.chunk.Chunk;
import cn.studykid.rag.core.chunk.Chunk.Locator;
import cn.studykid.rag.core.version.EditionCoordinate;

/**
 * 引用（Citation）——RAG 输出可溯源性的<b>载体</b>（RAG-D00 §2.6 决策 D-R08）。
 *
 * <p><b>⚠️ 与主平台既有 {@code HallucinationDetector} 的根本冲突（必须先读）</b>：
 * 主平台在 DEC-5「首期不做 RAG」的前提下，把<b>任何</b>形态的引用
 * （「第N章」「人教版」「第45页」「根据检索结果」）一律判为编造出处
 * （P3-D11 D-3）。那是<b>因为当时没有任何真实语料</b>——
 * 无语料时「提到出处」这个动作本身就是错的。
 *
 * <p>本项目引入真实语料后，那条规则的<b>前提消失</b>，必须<b>反转</b>为：
 * <blockquote>
 * <b>引用必须存在，且必须指向一个可核验的语料位置。</b>
 * 没有引用 = 不可验证 = 拒绝；引用了但指向不存在的位置 = 编造 = 拒绝。
 * </blockquote>
 * 这不是「放宽」FR-020，而是把 FR-020 从「禁止引用」升级为
 * 「<b>强制可核验引用</b>」——判定标准从「模式匹配」升级为
 * 「<b>存在性 + 位置校验 + 蕴含校验</b>」，判据<b>更强</b>而非更弱。
 *
 * <p><b>引用的四要素</b>（缺一不可）：
 * <ol>
 *   <li><b>版本坐标</b>——哪一册哪一版；</li>
 *   <li><b>物理位置</b>——第几页、第几题、章节路径；</li>
 *   <li><b>原文引文</b>——被引内容的<b>逐字</b>摘录（供人工核对）；</li>
 *   <li><b>支撑声明</b>——这条引用支撑回答中的哪一句。</li>
 * </ol>
 *
 * <p><b>⚠️ 逐字引文不可省略</b>：只给「第45页」而不给原文，
 * 学生无法核对、审计无法复核，引用退化为<b>装饰</b>。
 */
public record Citation(
        @NonNull String chunkId,
        @NonNull EditionCoordinate edition,
        @NonNull String documentTitle,
        @NonNull Locator locator,
        @NonNull String verbatimQuote,
        @NonNull String supportedClaim
) {

    public Citation {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (edition == null) {
            throw new IllegalArgumentException("edition 不能为空");
        }
        if (documentTitle == null || documentTitle.isBlank()) {
            throw new IllegalArgumentException("documentTitle 不能为空");
        }
        if (locator == null) {
            throw new IllegalArgumentException("locator 不能为空");
        }
        if (verbatimQuote == null || verbatimQuote.isBlank()) {
            // ⚠️ 无逐字引文 = 不可核对 = 不可验证。这类引用必须被拒绝。
            throw new IllegalArgumentException("verbatimQuote 不能为空：引用必须可人工核对");
        }
        if (supportedClaim == null || supportedClaim.isBlank()) {
            throw new IllegalArgumentException("supportedClaim 不能为空");
        }
    }

    /**
     * 由语料片段构造引用。
     *
     * @param chunk 被引片段
     * @param documentTitle 文档标题
     * @param quote 逐字引文（必须是 chunk 文本的子串）
     * @param supportedClaim 本引用支撑的声明
     * @return 引用
     */
    public static @NonNull Citation of(@NonNull Chunk chunk,
                                       @NonNull String documentTitle,
                                       @NonNull String quote,
                                       @NonNull String supportedClaim) {
        if (!chunk.text().contains(quote)) {
            // ⚠️ 引文必须真实存在于被引片段中 —— 防止「引用了 A 却引了 B 的内容」。
            throw new IllegalArgumentException("引文必须是被引片段文本的子串：" + quote);
        }
        return new Citation(chunk.id(), chunk.edition(), documentTitle, chunk.locator(),
                quote, supportedClaim);
    }

    /**
     * @param chunk 被引片段
     * @return 引用是否指向该片段（防止引用与内容错配）。
     */
    public boolean pointsTo(@NonNull Chunk chunk) {
        return chunkId.equals(chunk.id())
                && edition.sameAs(chunk.edition())
                && locator.equals(chunk.locator());
    }

    /**
     * @return 面向用户的完整引用串，如
     * 「《人教版·七年级上册·2024修订》第 3 章 > 3.2 二次函数的图像 · 第 45 页 · 第 12 题」
     */
    public @NonNull String display() {
        return "《" + documentTitle + "·" + edition.display() + "》" + locator.display();
    }

    /**
     * 一组引用。
     *
     * <p>⚠️ 集合级不变量：<b>组内引用不得来自互相冲突的版本</b>，
     * 除非调用方显式声明为跨版本对比场景。
     */
    public record CitationSet(@NonNull List<Citation> citations) {

        public CitationSet {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public boolean isEmpty() {
            return citations.isEmpty();
        }

        public int size() {
            return citations.size();
        }

        /**
         * @param claim 回答中的声明
         * @return 支撑该声明的引用（可能多条）
         */
        public @NonNull List<Citation> supporting(@NonNull String claim) {
            return citations.stream()
                    .filter(c -> c.supportedClaim().equals(claim))
                    .toList();
        }

        /**
         * @return 集合内出现的所有版本坐标
         */
        public @NonNull List<EditionCoordinate> editions() {
            return citations.stream().map(Citation::edition).distinct().toList();
        }

        /**
         * @return 是否存在版本混用（跨版本对比场景下允许）。
         */
        public boolean hasMixedEditions() {
            return editions().size() > 1;
        }
    }
}
