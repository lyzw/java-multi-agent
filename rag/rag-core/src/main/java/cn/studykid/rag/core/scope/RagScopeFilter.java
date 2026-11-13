package cn.studykid.rag.core.scope;

import java.util.Locale;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 行级隔离的唯一决策权威 —— RAG 侧的 {@code ScopeFilter}（RLS 清理 · M3）。
 *
 * <p><b>为什么需要它</b>：RAG 现有 13 张 FORCE RLS 表的读策略是
 * {@code tenant_id = ANY(ARRAY[rag_current_tenant(), rag_global_tenant_id()])}，
 * 其中 {@code rag_current_tenant()} <b>真读会话变量</b> {@code app.tenant_id}：
 * <ul>
 *   <li>未绑定 ⇒ 返回 NULL ⇒ {@code IN (NULL, 哨兵)} 恒不匹配 ⇒
 *       <b>租户语料静默消失</b>（不报错，检索恒空）；</li>
 *   <li>填哨兵 ⇒ 直接 RAISE 42501（越权封堵）。</li>
 * </ul>
 * 这正是「依赖 GUC 才能工作」的谓词形态。本类把这层决策<b>搬回应用层</b>：
 * 作用域先由本类判定（纯逻辑、可单测），再由方言渲染器翻成 SQL 谓词。
 *
 * <p><b>⚠️ 与主平台 {@code cn.studykid.multiagent.core.security.ScopeFilter} 同构但独立</b>：
 * RAG 是<b>独立 reactor</b>（{@code rag-core} 编译期依赖仅 JSpecify，
 * 由 {@code RagCorePurityTest} 钉死），platform 主代码也<b>零</b>引用
 * {@code cn.studykid.rag}（单向调用约定：platform → RAG 走 HTTP）。
 * 两边不能共享类型，否则要么打破领域层纯度，要么把两个部署单元焊死在一起。
 * <b>形态保持一致</b>是为了让两边的审计口径可比，不是为了共享代码。
 *
 * <p><b>⚠️ 哨兵租户绝不能成为会话身份</b>（与 {@link RagScope#isGlobalTenant(String)} 同源纪律）：
 * {@code TenantOnly(哨兵)} 表达的是「<b>只</b>看全局桶」的读口径（管理面 PLATFORM 档），
 * <b>不是</b>「以全局身份访问任何东西」。真正的越权封堵仍在入口层（{@code RagTenantContext} /
 * {@code RagRetrievalController#requireTenant}）与数据库层（{@code rag_current_tenant()} 的 RAISE）。
 */
public sealed interface RagScopeFilter
        permits RagScopeFilter.TenantOnly,
                RagScopeFilter.TenantWithGlobal,
                RagScopeFilter.GlobalOnly,
                RagScopeFilter.None {

    /**
     * 仅本租户行。
     *
     * <p>等价于现役 SQL：{@code tenant_id = rag_current_tenant()}。
     *
     * @param tenantId 本租户 ID（非空、非哨兵）
     */
    record TenantOnly(@NonNull String tenantId) implements RagScopeFilter {
        public TenantOnly {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId 不能为空");
            }
            if (RagScope.isGlobalTenant(tenantId)) {
                // ⚠️ 这里挡的是「把哨兵当成一个普通租户来查」：
                //    哨兵桶是平台全局语料，只能经 GlobalOnly 口径显式访问；
                //    混进 TenantOnly 会让「本租户」这个语义在审计日志里失真。
                throw new IllegalArgumentException(
                        "TenantOnly 不得携带全局哨兵（那是 GlobalOnly 的语义）：" + tenantId);
            }
        }
    }

    /**
     * 本租户行 ∪ 全局桶行。
     *
     * <p>等价于现役 SQL：{@code tenant_id IN (rag_current_tenant(), rag_global_tenant_id())}。
     *
     * <p><b>⚠️ 这不是「冗余放宽」而是检索口径的一部分</b>：全局桶承载平台公共语料
     * （官方教辅等），检索必须能召回它；写成 {@code = 本租户} 会把全局语料静默滤空
     * （实测：老写法 1 行 / 新写法 2 行，见 {@code scripts/rag-corpus-scope-counterproof.sh}）。
     */
    record TenantWithGlobal(@NonNull String tenantId) implements RagScopeFilter {
        public TenantWithGlobal {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId 不能为空");
            }
            if (RagScope.isGlobalTenant(tenantId)) {
                throw new IllegalArgumentException(
                        "TenantWithGlobal 的 tenantId 不得是全局哨兵（哨兵桶由 GlobalOnly 表达）：" + tenantId);
            }
        }
    }

    /**
     * 仅全局桶行（平台全局语料）。
     *
     * <p>等价于现役 SQL：{@code tenant_id = rag_global_tenant_id()}。
     *
     * <p><b>⚠️ 这是一个「可见行范围」，不是「会话身份」</b>：
     * 用它去 {@code set_config('app.tenant_id', 哨兵)} 会被数据库层 RAISE 42501 拒绝
     * （迁移 V8 §1 的越权封堵）。管理面 PLATFORM 档的正确形态是
     * 「<b>不</b>设会话租户 + 谓词绑定哨兵」两层同向收紧。
     */
    record GlobalOnly() implements RagScopeFilter {
    }

    /**
     * 空集：鉴权通过但无可见范围 ⇒ 恒假 ⇒ 0 行（fail-closed 的显式表达）。
     *
     * <p><b>⚠️ 它与「查不到数据」不是一回事</b>：{@code None} 表示<b>决策层</b>
     * 判定「这一行不该被看见」，而不是「这一行不存在」。二者混同会掩盖权限缺陷。
     */
    record None() implements RagScopeFilter {
    }

    /** 便捷常量。{@code switch} 中须用类型模式 {@code case RagScopeFilter.None n} 满足密封穷尽性。 */
    RagScopeFilter NONE = new None();

    /**
     * 解析「当前作用域」并归一化。
     *
     * <p><b>⚠️ 非法值报错而不是回落缺省</b>：调用方传了一个既不是 UUID 也不是哨兵的值，
     * 说明契约已被破坏；静默按缺省处理会让「以为在看 A、实际在看 B」完全不可见。
     *
     * @param tenantId 原始租户标识（可空 ⇒ 归一为 {@link #NONE}）
     * @param allowGlobal 是否允许直接以哨兵表达「仅全局桶」
     * @return 归一化后的作用域
     */
    @NonNull
    static RagScopeFilter parse(@Nullable String tenantId, boolean allowGlobal) {
        if (tenantId == null || tenantId.isBlank()) {
            return NONE;
        }
        String normalized = tenantId.trim();
        if (RagScope.isGlobalTenant(normalized)) {
            if (!allowGlobal) {
                throw new IllegalArgumentException(
                        "全局哨兵不得作为会话身份（越权企图）：" + normalized);
            }
            return new GlobalOnly();
        }
        // ⚠️ 大小写归一：UUID 从 HTTP 头进来，大写形态必须与库内存储同值，
        //    否则谓词 `tenant_id = ?` 拿字符串比 uuid 列会直接抛类型错。
        return new TenantWithGlobal(normalized.toLowerCase(Locale.ROOT));
    }
}
