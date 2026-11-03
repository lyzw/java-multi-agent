package cn.studykid.multiagent.infrastructure.persistence.scope;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * SQL 谓词片段 —— 带命名参数的 SQL 片段（12 文 §4.1）。
 *
 * <p>{@link #TRUE} / {@link #FALSE} 供 {@code Unrestricted} / {@code None} 渲染，
 * 分别是「恒真 → 全行可见」与「恒假 → 0 行（fail-closed）」。
 */
public record SqlFragment(@NonNull String sql, @NonNull Map<String, Object> params) {

    /** 恒真谓词（Unrestricted 渲染结果）。 */
    public static final SqlFragment TRUE = new SqlFragment("1=1", Map.of());

    /** 恒假谓词（None 渲染结果 → 0 行，fail-closed）。 */
    public static final SqlFragment FALSE = new SqlFragment("1=0", Map.of());
}
