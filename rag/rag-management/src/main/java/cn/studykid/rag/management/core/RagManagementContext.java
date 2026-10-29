package cn.studykid.rag.management.core;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.scope.RagCorpusScope;
import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 管理面一次调用的<b>作用域上下文</b>（RAG-D11 第四刀 · 第一刀）。
 *
 * <p><b>与 {@link RagTenantContext} 的分工</b>：
 * <ul>
 *   <li>{@code RagTenantContext} 回答「<b>以哪个租户的身份</b>执行」——
 *       它是会话身份，租户 ID 非空且<b>绝不等于哨兵</b>（那层拒绝是越权闸门，
 *       本类不放宽它，只是<b>不再让所有端点都被它挡在门外</b>）；</li>
 *   <li>本类回答「<b>这次查的是哪一批行</b>」——租户档 / 平台全局档 / 跨租户盘点档。</li>
 * </ul>
 * 写路径继续只收 {@code RagTenantContext}（见 {@link #requireTenant()}），
 * 因此「平台全局语料的写通道」在本刀根本不存在入口，而不是「有入口但被 if 拦住」。
 *
 * <p><b>⚠️ 会话租户与查询批次是两个值，不是一回事</b>：
 * <ul>
 *   <li>{@link #sessionTenantId()} —— 要写进会话变量 {@code app.tenant_id} 的值；
 *       平台档为 {@code null}，表示<b>不建立会话租户</b>，
 *       于是 {@code rag_current_tenant()} 返回 NULL、RLS 读策略只放行全局行；</li>
 *   <li>{@link #queryTenantId()} —— 要绑进读侧 SQL 租户谓词的值；
 *       平台档为 {@link RagScope#GLOBAL_TENANT_ID} 哨兵。</li>
 * </ul>
 * 把二者混成一个值会立刻出事：拿哨兵当会话身份，
 * {@code rag_current_tenant()} 直接抛 SQLSTATE 42501（迁移 V8 §1 的封堵），
 * 表现为一条难以定位的 500。
 *
 * @param scope 作用域档位
 * @param tenant 租户上下文；仅 {@link RagCorpusScope#TENANT} 档非空
 * @param minTrustTier 权威门槛（与 {@code RagTenantContext} 同源，统计口径用）
 */
public record RagManagementContext(
        @NonNull RagCorpusScope scope,
        @Nullable RagTenantContext tenant,
        @NonNull TrustTier minTrustTier
) {

    public RagManagementContext {
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (minTrustTier == null) {
            throw new IllegalArgumentException("minTrustTier 不能为空");
        }
        if (scope == RagCorpusScope.TENANT && tenant == null) {
            throw new IllegalArgumentException("TENANT 作用域必须携带租户上下文");
        }
        if (scope != RagCorpusScope.TENANT && tenant != null) {
            throw new IllegalArgumentException(
                    scope + " 作用域不接受租户上下文（会话租户保持未设置，避免冒充租户身份）");
        }
    }

    /** 租户档：会话租户与查询租户同为该租户，与本刀之前逐字节一致。 */
    @NonNull
    public static RagManagementContext forTenant(@NonNull RagTenantContext tenant) {
        return new RagManagementContext(RagCorpusScope.TENANT, tenant, tenant.minTrustTier());
    }

    /**
     * 平台全局档：只允许读（写通道属第二刀）。
     *
     * @param minTrustTier 门槛（缺省由控制器按 {@code OFFICIAL_TUTORIAL} 回落）
     * @return 平台全局作用域上下文
     */
    @NonNull
    public static RagManagementContext forPlatform(@NonNull TrustTier minTrustTier) {
        return new RagManagementContext(RagCorpusScope.PLATFORM, null, minTrustTier);
    }

    /**
     * 跨租户盘点档：占位。本刀 {@code rag_app} 角色无跨租户可见性，
     * 控制器在到达本方法前就以 501 拒绝；留着是为了让「第三刀要接的点」有名字。
     *
     * @param minTrustTier 门槛
     * @return 跨租户作用域上下文
     */
    @NonNull
    public static RagManagementContext forAll(@NonNull TrustTier minTrustTier) {
        return new RagManagementContext(RagCorpusScope.ALL, null, minTrustTier);
    }

    /** 写进会话变量 {@code app.tenant_id} 的值；平台档/盘点档返回 {@code null} 表示「不设置」。 */
    @Nullable
    public String sessionTenantId() {
        return scope == RagCorpusScope.TENANT ? tenant.tenantId() : null;
    }

    /**
     * 绑进读侧 SQL 租户谓词的值：租户档取该租户，平台档取全局哨兵。
     *
     * <p>⚠️ 与 {@link #sessionTenantId()} <b>刻意不是同一个值</b>：
     * 平台档的会话租户必须保持未设置（把哨兵写进 {@code app.tenant_id}，
     * {@code rag_current_tenant()} 会直接抛 SQLSTATE 42501——迁移 V8 §1 的封堵）；
     * 而谓词这边要的正是「这一批行的租户列等于哨兵」。
     *
     * @return 归一化后的 UUID 字符串
     */
    @NonNull
    public String queryTenantId() {
        return scope == RagCorpusScope.TENANT
                ? String.valueOf(UUID.fromString(tenant.tenantId()))
                : RagScope.GLOBAL_TENANT_ID;
    }

    /**
     * 写路径取租户上下文。
     *
     * <p>⚠️ 平台档调用本方法会抛 {@link IllegalStateException} ——
     * 这是<b>刻意的入口缺失</b>而非疏漏：全局语料的写通道必须走独立运维数据源
     * （第二刀），不能借道 {@code rag_app} 连接，否则会同时打开 RAG-D11 §2.1
     * 那条「应用角色写不出全局行 = 越权闸门」。
     *
     * @return 租户上下文
     */
    @NonNull
    public RagTenantContext requireTenant() {
        if (tenant == null) {
            throw new IllegalStateException(
                    "平台全局语料的写通道尚未开放（需独立运维数据源，属第四刀第二档）；"
                            + "当前 " + scope + " 作用域只读");
        }
        return tenant;
    }
}
