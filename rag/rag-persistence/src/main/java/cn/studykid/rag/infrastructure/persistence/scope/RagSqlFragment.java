package cn.studykid.rag.infrastructure.persistence.scope;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * SQL 谓词片段 —— 带<b>位置参数</b>的 SQL 片段（RLS 清理 · M3）。
 *
 * <p><b>为什么用位置参数（{@code ?}）而不是命名参数</b>：RAG 侧全部走
 * {@link org.springframework.jdbc.core.JdbcTemplate}（无
 * {@code NamedParameterJdbcTemplate}），位置参数是既有调用形态的最小改动面。
 * 参数顺序由 {@link RagSqlScopeTranslator} 统一产出，调用方按同一顺序追加，
 * 避免「手写 SQL 时把参数顺序搞错」这类静默失真。
 *
 * <p><b>⚠️ 片段里的 {@code ?} 只能出现在谓词位置</b>，且必须与 {@link #params()} 一一对应。
 */
public record RagSqlFragment(String sql, java.util.List<Object> params) {

    public RagSqlFragment {
        if (sql == null) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        if (params == null) {
            throw new IllegalArgumentException("params 不能为空");
        }
        java.util.List<Object> copy = java.util.List.copyOf(params);
        long qmarks = sql.chars().filter(c -> c == '?').count();
        if (qmarks != copy.size()) {
            // ⚠️ 占位符与参数个数不一致 ⇒ JDBC 会在运行期抛一个与真实原因无关的错
            //    （"The index 1 is out of range"），排查成本极高。此处提前失败。
            throw new IllegalArgumentException(
                    "SQL 片段占位符数(" + qmarks + ")与参数数(" + copy.size() + ")不一致："
                            + sql);
        }
        params = copy;
    }

    /** 恒假谓词（{@code None} 渲染结果 → 0 行，fail-closed）。 */
    public static final RagSqlFragment FALSE = new RagSqlFragment("1 = 0", java.util.List.of());

    /** 恒真谓词（保留给运维通道的全域读；生产读面不用于租户数据）。 */
    public static final RagSqlFragment TRUE = new RagSqlFragment("1 = 1", java.util.List.of());

    /** 参数视图的只读映射形式（供需要 Map 的调用方使用）。 */
    @NonNull
    public Map<Integer, Object> indexedParams() {
        java.util.Map<Integer, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < params.size(); i++) {
            map.put(i, params.get(i));
        }
        return map;
    }
}
