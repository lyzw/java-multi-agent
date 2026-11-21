package cn.studykid.rag.management.infrastructure;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.knowledge.KnowledgePoint;
import cn.studykid.rag.infrastructure.rls.RagTenantScope;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.infrastructure.mapper.RagKpAliasMapper;
import cn.studykid.rag.management.ports.KnowledgeAliasPort;

/**
 * 知识点别名仓储 —— MyBatis-Plus 适配层（W1 RAG 试点）。
 *
 * <p>Port 签名与旧 {@code JdbcKnowledgeAliasRepository} 完全一致，上层零改动；
 * 内部委托 {@link RagKpAliasMapper}，并保留三条业务纪律：
 * <ol>
 *   <li><b>别名归一化</b>：{@link KnowledgePoint#normalize}，与匹配期同口径；</li>
 *   <li><b>RLS 会话绑定</b>：经 {@link RagTenantScope.bind} + 拦截器在事务内 set_config；
 *       平台档 sessionTenantId=null → 置空串（rag_current_tenant() 返回 NULL，只剩全局行）；</li>
 *   <li><b>事务</b>：沿用 Spring {@link TransactionTemplate}（与原实现一致）。</li>
 * </ol>
 *
 * <p>⚠️ 默认关闭（{@code persistence.mybatis.enabled=true} 才注册），旧实现互补条件保留。
 */
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisKnowledgeAliasRepository implements KnowledgeAliasPort {

    private final RagKpAliasMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public MybatisKnowledgeAliasRepository(RagKpAliasMapper mapper,
                                            TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int addAlias(RagManagementContext context, String kpId, String alias) {
        String normalized = KnowledgePoint.normalize(alias);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("别名不能为空");
        }
        return inScope(context, () -> transactionTemplate.execute(tx -> mapper.insertIfAbsent(
                UUID.randomUUID(), UUID.fromString(requireTenantId(context)),
                UUID.fromString(kpId), normalized)));
    }

    @Override
    public List<String> listAliases(RagManagementContext context, String kpId) {
        return inScope(context, () -> transactionTemplate.execute(tx -> mapper.listAliases(
                UUID.fromString(requireTenantId(context)), UUID.fromString(kpId))));
    }

    @Override
    public int removeAlias(RagManagementContext context, String kpId, String alias) {
        String normalized = KnowledgePoint.normalize(alias);
        return inScope(context, () -> transactionTemplate.execute(tx -> mapper.removeAlias(
                UUID.fromString(requireTenantId(context)), UUID.fromString(kpId), normalized)));
    }

    @Override
    public @NonNull List<KnowledgePointBrief> listKnowledgePoints(@NonNull RagManagementContext context,
                                                                   String keyword, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 > 0");
        }
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return inScope(context, () -> transactionTemplate.execute(tx -> {
            // ⚠️ 2026-11-21 H1 修复：不再用 "'" + tenantId + "'::uuid" 字符串拼进 ${}。
            //    先 parse 成 UUID（非法 UUID 立即抛 IllegalArgumentException，fail-closed），
            //    再塞进 List<UUID> 由 MyBatis 以 JDBC 参数形式传给 ANY(#{tenantUuids}::uuid[])，
            //    PG 自动做数组类型绑定，彻底消除 SQL 注入面。
            UUID tenantUuid = UUID.fromString(requireNonBlank(context.queryTenantId()));
            List<UUID> tenantUuids = List.of(tenantUuid, RAG_GLOBAL_TENANT);
            List<KnowledgePointBrief> rows = mapper.listKnowledgePoints(tenantUuids, kw, limit);
            return rows == null ? List.of() : rows;
        }));
    }

    /** RAG 全局租户 UUID（对应 SQL 函数 rag_global_tenant_id() 返回值）。 */
    private static final UUID RAG_GLOBAL_TENANT = UUID.fromString(
            "00000000-0000-0000-0000-000000000000");

    /** 防御性校验：queryTenantId 非空，否则 fail-closed。 */
    private static String requireNonBlank(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("当前上下文无租户 ID，无法执行知识点查询（应在 RagTenantScope.bind 后调用）");
        }
        return id;
    }

    /** 绑定会话作用域（platform 档置空串），执行后无条件清理。 */
    private <T> T inScope(RagManagementContext context, java.util.function.Supplier<T> action) {
        RagTenantScope.bind(context.sessionTenantId());
        try {
            return action.get();
        } finally {
            RagTenantScope.clear();
        }
    }

    /** 别名表要求真实租户（平台档无别名概念），与原实现 requireTenant() 一致。 */
    private static String requireTenantId(RagManagementContext context) {
        return context.requireTenant().tenantId();
    }
}
