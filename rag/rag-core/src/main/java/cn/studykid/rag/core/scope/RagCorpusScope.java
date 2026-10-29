package cn.studykid.rag.core.scope;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * 管理面的三档作用域（RAG-D11 第四刀 · 本档为读侧）。
 *
 * <p><b>为什么作用域要由请求头显式声明，而不是「有没有传 {@code X-Tenant-Id}」隐式推断</b>：
 * 隐式推断会让「忘记传租户」这类本地配置错误<b>静默变成一次跨租户或全局查询</b>。
 * 而 {@code RagManagementController#requireTenantContext} 的既有纪律恰恰是
 * 「缺租户头就 400，绝不静默返回空」——缺省值恒为 {@link #TENANT}，
 * 这条纪律在本刀之后依然成立。
 *
 * <p><b>三档语义</b>：
 * <table border="1">
 *   <caption>作用域档位</caption>
 *   <tr><th>档位</th><th>会话租户 {@code app.tenant_id}</th><th>SQL 租户谓词</th><th>RLS 实际可见</th><th>可写</th></tr>
 *   <tr><td>{@link #PLATFORM}</td><td><b>不设</b>（→ {@code rag_current_tenant()} 为 NULL）</td>
 *       <td>绑定 {@link RagScope#GLOBAL_TENANT_ID}</td><td>仅 {@code scope='PLATFORM'} 全局行</td>
 *       <td>否（写通道属第二刀）</td></tr>
 *   <tr><td>{@link #TENANT}</td><td>设为该租户</td><td>绑定该租户</td><td>仅本租户行</td><td>是（行为与本刀之前逐字节一致）</td></tr>
 *   <tr><td>{@link #ALL}</td><td>不设</td><td>不加租户谓词</td><td>需运维数据源（第三刀）</td><td>否（只读盘点）</td></tr>
 * </table>
 *
 * <p><b>⚠️ {@link #PLATFORM} 档的双层过滤在本刀是「同向收紧」的</b>：
 * 会话租户为 NULL 时 RLS 读策略只放行全局行，SQL 谓词再绑定哨兵——
 * 两层各自独立成立。若将来某条 SQL 误绑了真实租户，
 * 结果是「0 行」而不是「泄漏该租户数据」，这正是选择「不设会话租户」的理由。
 */
public enum RagCorpusScope {

    /** 平台全局语料（哨兵租户桶）。 */
    PLATFORM,
    /** 单个租户的私有语料（缺省档，与本刀之前一致）。 */
    TENANT,
    /** 跨全部租户的盘点视图（第三刀，需运维数据源）。 */
    ALL;

    /** 请求头名：值为本枚举名，缺省 {@link #TENANT}。 */
    public static final String HEADER = "X-Rag-Scope";

    /**
     * 解析请求头。
     *
     * <p>⚠️ <b>非法值报错而不是回落缺省</b>：调用方传了 {@code PLATFORME} 这种错值，
     * 说明契约被破坏；静默按 {@code TENANT} 处理会让「以为在看全局、实际在看租户」
     * 这件事完全不可见。
     *
     * @param raw 头原始值（可为 null／空白 → {@link #TENANT}）
     * @return 作用域枚举
     * @throws IllegalArgumentException 值不属于三档之一
     */
    public static RagCorpusScope parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return TENANT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (RagCorpusScope scope : values()) {
            if (scope.name().equals(normalized)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("未知的语料作用域：" + raw
                + "（合法值：PLATFORM / TENANT / ALL；缺省 TENANT）");
    }

    /** 本档是否需要平台域身份（即不接受租户令牌）。 */
    public boolean requiresPlatformCaller() {
        return this != TENANT;
    }
}
