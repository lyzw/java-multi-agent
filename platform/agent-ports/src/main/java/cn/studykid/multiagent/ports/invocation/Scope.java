package cn.studykid.multiagent.ports.invocation;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 能力调用作用域（Scope）：全平台一套 PLATFORM / TENANT / ALL，禁止隐式推断。
 *
 * <p><b>三档语义</b>（对齐 RAG 侧 {@code RagCorpusScope} 与《松绑方案》的三档作用域）：
 * <ul>
 *   <li>{@link #PLATFORM}：平台全局，数据落在哨兵桶；写须治理票据；无 tenantId；</li>
 *   <li>{@link #TENANT}：单租户，tenantId 必填且必须来自已验签上下文，不得由 Agent 自报；</li>
 *   <li>{@link #ALL}：全租户盘点，<b>恒只读、不出正文</b>（红线），无 tenantId。</li>
 * </ul>
 *
 * <p><b>禁止隐式推断</b>：没有任何"没传就用默认租户"的隐式回落写语义。解析请求时
 * TENANT 档缺 tenantId 一律拒绝；平台默认租户只允许作为<b>只读</b>的兜底视图
 * （由 {@link InvocationContext#scopeExplicit()} 标记，见其文档）。
 */
public enum Scope {
    PLATFORM,
    TENANT,
    ALL;

    /** 是否需要显式 tenantId。 */
    public boolean requiresTenantId() {
        return this == TENANT;
    }

    /** 是否允许写操作。ALL 档恒只读。 */
    public boolean allowsWrite() {
        return this != ALL;
    }

    /** 解析请求头字符串；非法/空一律 400（由调用方映射），绝不静默回落。 */
    public static @Nullable Scope parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 该档位在审计 ticket_scope 列里的规范值（与库侧 CHECK 同源）。 */
    public @NonNull String auditValue() {
        return name();
    }
}
