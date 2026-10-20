package cn.studykid.rag.core.version;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 版本检索范围（RAG-D00 §2.2 决策 D-R04）。
 *
 * <p>表达「本次检索允许命中哪些版本」，是<b>过滤条件</b>而非排序偏好。
 *
 * <p><b>三档策略</b>（对应三种真实业务场景）：
 * <ul>
 *   <li>{@link Mode#STRICT}：<b>只允许学生当前在用的版本</b>。
 *       用于「按我的书讲」这类请求——学生手上就是那一版，
 *       换版内容会造成困惑。</li>
 *   <li>{@link Mode#PREFERRED_WITH_FALLBACK}：优先该版本，
 *       无命中时允许同<b>册</b>的其他版次，并<b>显式标注差异</b>。
 *       用于「这个知识点讲了什么」这类概念性请求。</li>
 *   <li>{@link Mode#CROSS_EDITION}：跨版本检索，用于「不同版本编排差异」类问题。
 *       结果必须<b>按版本分组</b>呈现，不允许混排。</li>
 * </ul>
 *
 * <p><b>⚠️ 不可妥协</b>：{@link Mode#STRICT} 下<b>不允许</b>跨版本回退。
 * 找不到就说找不到（交给上层决定是否追问学生），
 * 绝不「找个差不多的版本凑合」——那会让学生按错误的页码去翻书。
 */
public record EditionScope(
        @NonNull Mode mode,
        @NonNull EditionCoordinate preferred
) {

    public EditionScope {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (preferred == null) {
            throw new IllegalArgumentException("preferred 不能为空");
        }
    }

    /** 严格锁定单一版本。 */
    public static @NonNull EditionScope strict(@NonNull EditionCoordinate coordinate) {
        return new EditionScope(Mode.STRICT, coordinate);
    }

    /** 优先该版本，允许同册其他版次回退。 */
    public static @NonNull EditionScope preferredWithFallback(@NonNull EditionCoordinate coordinate) {
        return new EditionScope(Mode.PREFERRED_WITH_FALLBACK, coordinate);
    }

    /** 跨版本对比。 */
    public static @NonNull EditionScope crossEdition(@NonNull EditionCoordinate coordinate) {
        return new EditionScope(Mode.CROSS_EDITION, coordinate);
    }

    /**
     * 判断某个版本的语料是否<b>允许</b>进入本次结果集。
     *
     * @param candidate 待判断的语料版本
     * @return 是否允许
     */
    public boolean admits(@NonNull EditionCoordinate candidate) {
        return switch (mode) {
            case STRICT -> candidate.sameAs(preferred);
            case PREFERRED_WITH_FALLBACK -> candidate.sameBook(preferred);
            case CROSS_EDITION -> true;
        };
    }

    /**
     * @param candidate 待判断的语料版本
     * @return 若该命中来自<b>非首选版本</b>，是否需要在回答中<b>显式标注差异</b>
     */
    public boolean requiresVersionDisclaimer(@NonNull EditionCoordinate candidate) {
        if (mode == Mode.CROSS_EDITION) {
            return true;
        }
        return !candidate.sameAs(preferred);
    }

    /**
     * @param candidate 待判断的语料版本
     * @return 该命中相对首选版本的<b>排序惩罚</b>（越大越靠后；0 表示首选）
     *
     * <p>⚠️ 这是<b>排序</b>惩罚，不是<b>过滤</b>。过滤由 {@link #admits} 决定。
     * 二者混淆会导致「降权后仍被召回」——那正是版本串扰的成因。
     */
    public int rankPenalty(@NonNull EditionCoordinate candidate) {
        if (candidate.sameAs(preferred)) {
            return 0;
        }
        if (candidate.sameBook(preferred)) {
            return 10;
        }
        return 50;
    }

    /** @return 若范围中允许存在多个版本，返回需要标注差异的版本集合。 */
    public @NonNull Optional<String> disclaimerHint() {
        return mode == Mode.STRICT
                ? Optional.empty()
                : Optional.of("以下内容来自与首选版本不同的教材版本，已标注差异");
    }

    /** 范围模式。 */
    public enum Mode {
        /** 严格锁定。 */
        STRICT,
        /** 首选 + 同册回退。 */
        PREFERRED_WITH_FALLBACK,
        /** 跨版本。 */
        CROSS_EDITION
    }
}
