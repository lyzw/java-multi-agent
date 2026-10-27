package cn.studykid.rag.core.scope;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * 全局 / 租户双作用域的判别约定（RAG-D11）。
 *
 * <p><b>为什么是「哨兵租户 ID」而不是「tenant_id 为空」</b>：
 * 语料链 {@code source → edition → document → chunk → embedding} 全靠
 * {@code (tenant_id, <父 id>)} 复合外键维系，且
 * {@code rag_knowledge_prerequisite} 的主键里就含 {@code tenant_id}。
 * 把该列放开成可空会同时踩中三处（V8 文件头有实测记录）：
 * <ol>
 *   <li>复合外键按 SQL 标准（MATCH SIMPLE）<b>任一列为 NULL 即不校验</b> ——
 *       全局片段可以指向一个不存在的载体，且完全静默；</li>
 *   <li>等值 JOIN {@code c.tenant_id = ed.tenant_id} 在两侧都是 NULL 时
 *       结果为 {@code NULL} 而非 {@code true} —— 全局语料<b>一行也召回不到</b>，
 *       必须把十几处 JOIN 全改成 {@code IS NOT DISTINCT FROM}；</li>
 *   <li>主键列不能为空：PostgreSQL 直接拒绝
 *       {@code ALTER TABLE rag_knowledge_prerequisite ALTER COLUMN tenant_id DROP NOT NULL}
 *       （{@code column "tenant_id" is in a primary key}）——
 *       「全局知识点的先决关系边」在 NULL 表示法下根本无法存在。</li>
 * </ol>
 * 哨兵表示法让这三处一律不变：列继续 {@code NOT NULL}，
 * 全局行的 {@code tenant_id} 取本类常量，语义由 {@code scope} 判别列自证。
 *
 * <p><b>⚠️ 唯一的代价：这个值绝不能成为会话身份</b>。
 * 数据库侧由 {@code rag.rag_current_tenant()} 直接抛错封死
 * （迁移 V8 §1），应用侧由本类的 {@link #isGlobalTenant(String)} 在
 * 租户上下文构造处再挡一次——纵深防御，两层都必须存在。
 *
 * <p>取值用 nil UUID：真实租户 ID 来自 {@code gen_random_uuid()} 或
 * platform 下发，与之碰撞概率为 0，且它一眼可辨、不会被误当成正常租户。
 */
public final class RagScope {

    /** 全局作用域的保留租户 ID（与 SQL 函数 {@code rag.rag_global_tenant_id()} 同值）。 */
    public static final String GLOBAL_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    private RagScope() {
    }

    /**
     * 判断给定租户 ID 是否是全局哨兵。
     *
     * <p>容忍大小写与空白：租户 ID 从 HTTP 头进来，任何写法都不该被放过。
     *
     * @param tenantId 待判别的租户 ID（可为 null）
     * @return true 表示它试图冒充全局作用域
     */
    public static boolean isGlobalTenant(@Nullable String tenantId) {
        return tenantId != null && GLOBAL_TENANT_ID.equals(tenantId.trim().toLowerCase(Locale.ROOT));
    }
}
