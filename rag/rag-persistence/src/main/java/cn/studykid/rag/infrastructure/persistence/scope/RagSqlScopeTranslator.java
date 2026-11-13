package cn.studykid.rag.infrastructure.persistence.scope;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.scope.RagScopeFilter;

/**
 * 作用域 → SQL 谓词的翻译器（RAG 侧，对应主平台的 {@code SqlScopeTranslator}）。
 *
 * <p><b>职责边界</b>：只把 {@link RagScopeFilter} 翻成「谓词文本 + 绑定参数」，
 * <b>不</b>知道表名、<b>不</b>知道业务语义、<b>不</b>执行任何 SQL。
 * 仓储只负责「把片段贴到自己的 WHERE 里」。
 *
 * <p><b>⚠️ 渲染结果必须与现役 RLS 策略逐字等价</b>（迁移 V8 读策略）：
 * <table border="1">
 *   <caption>渲染对照</caption>
 *   <tr><th>作用域</th><th>渲染谓词</th><th>现役 RLS 等价式</th></tr>
 *   <tr><td>{@link RagScopeFilter.TenantOnly}</td>
 *       <td>{@code tenant_id = ?::uuid}</td><td>{@code tenant_id = rag_current_tenant()}</td></tr>
 *   <tr><td>{@link RagScopeFilter.TenantWithGlobal}</td>
 *       <td>{@code tenant_id IN (?::uuid, ?::uuid)}</td>
 *       <td>{@code tenant_id IN (rag_current_tenant(), rag_global_tenant_id())}</td></tr>
 *   <tr><td>{@link RagScopeFilter.GlobalOnly}</td>
 *       <td>{@code tenant_id = ?::uuid}</td><td>{@code tenant_id = rag_global_tenant_id()}</td></tr>
 *   <tr><td>{@link RagScopeFilter.None}</td>
 *       <td>{@code 1 = 0}</td><td>（无对应：策略恒真，故必须由应用层显式收口）</td></tr>
 * </table>
 *
 * <p><b>⚠️ 哨兵从哪来</b>：本翻译器<b>不</b>在 SQL 里写 {@code rag_global_tenant_id()}，
 * 而是绑定 {@link RagScope#GLOBAL_TENANT_ID} 这个 Java 常量。
 * 理由：该 PG 函数是 IMMUTABLE 常量函数（不依赖 GUC，见 {@code authz/guc-dependency.md} G3），
 * 随 M5 删除；应用侧常量已与其同值（由 {@code RagScopeTest} 钉死字面量）。
 * 若继续在 SQL 里写函数名，M5 删除函数时会出现「一处漏改 ⇒ 运行期函数不存在」的静默炸点。
 */
public final class RagSqlScopeTranslator {

    /**
     * 列限定符白名单：标识符 或 标识符.标识符。
     *
     * <p>⚠️ 该表达式会被拼进 SQL，必须严格校验——否则就成了 SQL 注入面。
     */
    private static final java.util.regex.Pattern COLUMN_EXPR =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final String globalTenantId;

    /**
     * @param globalTenantId 全局哨兵租户 ID（须与 {@link RagScope#GLOBAL_TENANT_ID} 同值）
     */
    public RagSqlScopeTranslator(@NonNull String globalTenantId) {
        if (globalTenantId == null || globalTenantId.isBlank()) {
            throw new IllegalArgumentException("globalTenantId 不能为空（全局哨兵租户标识）");
        }
        this.globalTenantId = globalTenantId;
    }

    /** 用应用侧哨兵常量构造（推荐：与 {@link RagScope#GLOBAL_TENANT_ID} 同源，不会漂移）。 */
    @NonNull
    public static RagSqlScopeTranslator withDefaultSentinel() {
        return new RagSqlScopeTranslator(RagScope.GLOBAL_TENANT_ID);
    }

    /**
     * 把作用域渲染成 SQL 谓词片段（列名用<b>裸</b> {@code tenant_id}）。
     *
     * <p>⚠️ 只适用于<b>单表</b>查询（如 {@code FROM rag_knowledge_point}）。
     * 多表 JOIN 场景<b>必须</b>用 {@link #render(RagScopeFilter, String)} 给出列限定符，
     * 否则 PG 报 {@code column reference "tenant_id" is ambiguous}
     * ——JOIN 的每张表都有 {@code tenant_id} 列，而裸列名无法消歧。
     *
     * @param filter 作用域决策
     * @return 谓词文本 + 绑定参数（顺序即片段中 {@code ?} 的顺序）
     */
    @NonNull
    public RagSqlFragment render(@NonNull RagScopeFilter filter) {
        return render(filter, "tenant_id");
    }

    /**
     * 把作用域渲染成 SQL 谓词片段（指定列限定符）。
     *
     * <p>⚠️ <b>为什么列限定符由调用方给出、而不是翻译器内置</b>：
     * 同一条谓词在不同查询里指向不同的表别名（{@code c.tenant_id} / {@code ed.tenant_id}），
     * 翻译器无从得知。但<b>谓词形态</b>（{@code = ?} 还是 {@code IN (?, ?)}）
     * 必须由翻译器单点产出——那才是容易漂移的部分（全局语料被静默滤空的成因）。
     *
     * @param filter 作用域决策
     * @param columnExpr 列限定符（{@code tenant_id} 或 {@code c.tenant_id} 形态）
     * @return 谓词文本 + 绑定参数
     */
    @NonNull
    public RagSqlFragment render(@NonNull RagScopeFilter filter, @NonNull String columnExpr) {
        if (filter == null) {
            throw new IllegalArgumentException("filter 不能为空");
        }
        if (columnExpr == null || columnExpr.isBlank()) {
            throw new IllegalArgumentException("columnExpr 不能为空");
        }
        // ⚠️ 列限定符会被拼进 SQL ⇒ 必须走白名单校验，绝不接受任意字符串。
        //    允许形态：标识符 或 标识符.标识符（列别名.列名）。
        if (!COLUMN_EXPR.matcher(columnExpr).matches()) {
            throw new IllegalArgumentException(
                    "列限定符只能是「列名」或「别名.列名」形态，当前=" + columnExpr);
        }
        // ⚠️ tenant_id 是 uuid 列，绑定参数会被 PG 推断成 character varying，
        //    必须显式 ?::uuid 才能命中 uuid = uuid 操作符（否则报
        //    「operator does not exist: uuid = character varying」）。
        return switch (filter) {
            case RagScopeFilter.TenantOnly t ->
                    new RagSqlFragment(columnExpr + " = ?::uuid", List.of(t.tenantId()));
            case RagScopeFilter.TenantWithGlobal t ->
                    new RagSqlFragment(columnExpr + " IN (?::uuid, ?::uuid)",
                            List.of(t.tenantId(), globalTenantId));
            case RagScopeFilter.GlobalOnly g ->
                    new RagSqlFragment(columnExpr + " = ?::uuid", List.of(globalTenantId));
            // ⚠️ 恒假 → 0 行：fail-closed 的显式表达。
            //    这里刻意<b>不</b>回落到「查全部」——那是最危险的默认值。
            case RagScopeFilter.None n -> RagSqlFragment.FALSE;
        };
    }
}
