package cn.studykid.rag.infrastructure.rls;

import org.jspecify.annotations.NonNull;

/**
 * RAG 租户作用域（W1 RAG 试点，对应 platform 同名类）。
 *
 * <p>⚠️ 与 platform 的差别：RAG 的「平台档」RLS 语义是
 * <b>显式置空 app.tenant_id</b>（见 RagSessionScope），不是零 UUID 哨兵——
 * 把哨兵写进去会让 rag_current_tenant() 抛 SQLSTATE 42501。
 * 故本类允许空串作为合法绑定值，但保留「未 bind 直接 require → fail-closed」。
 */
public final class RagTenantScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RagTenantScope() {
    }

    /**
     * 绑定会话租户。platform 档传 {@code null} / 空串表示显式置空（RLS 只剩全局行）。
     */
    public static void bind(String sessionTenantId) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("RAG 租户作用域重复绑定：疑似线程池残留未清理");
        }
        CURRENT.set(sessionTenantId == null ? "" : sessionTenantId);
    }

    /** 取当前会话租户；未 bind 则 fail-closed（空串是合法的「平台档」值）。 */
    public static @NonNull String requireBound() {
        String v = CURRENT.get();
        if (v == null) {
            throw new IllegalStateException("无 RAG 会话作用域：Mapper 调用前必须 RagTenantScope.bind(...)");
        }
        return v;
    }

    /** 必须在 finally 中无条件调用。 */
    public static void clear() {
        CURRENT.remove();
        RagRlsBindingInterceptor.onConnectionRelease();
    }
}
