package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigDraft;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.ConfigDraftMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.ConfigDraftDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.ConfigDraftPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 变更草稿仓储 —— MyBatis-Plus 适配层（POC，对应主文档 §4.2 / §9.3）。
 *
 * <p><b>范式样板</b>：类名/包名/Port 签名与旧 {@code JdbcConfigDraftRepository} 完全一致，
 * 上层 service 零改动；内部不再写 SQL，改为委托 {@link ConfigDraftMapper}。
 * 其他 76 个 Jdbc*Repository 按此模板逐个替换。
 *
 * <p>⚠️ 三个关键纪律（逐条对照主文档 §9.3 checklist）：
 * <ol>
 *   <li><b>TenantScope 绑定</b>：每个 Mapper 调用前用<b>认证上下文传入的 tenantId</b>（非客户端参数）
 *       调 {@code TenantScope.bind}，finally 中无条件 {@code clear} + 拦截器标记清理；</li>
 *   <li><b>游标分页 / JSONB / 复杂 SQL</b> 交给 XML，limit+1 多取的行在此裁掉并生成 nextCursor；</li>
 *   <li><b>状态迁移 CAS</b>：条件 UPDATE 返回 0 行 → fail-closed 抛异常（不静默成功）。</li>
 * </ol>
 *
 * <p>⚠️ 事务：沿用本项目显式连接事务（{@link TransactionalConnectionHolder}），
 * 保证 MyBatis 的 SpringManagedTransaction 拿到的是同一条连接（RLS 绑定才能生效）。
 * 过渡期若外层已有事务连接，拦截器复用之，不另开。
 */
@Repository
public class MybatisConfigDraftRepository implements ConfigDraftPort {

    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final ConfigDraftMapper mapper;

    public MybatisConfigDraftRepository(@NonNull ConfigDraftMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public @NonNull ConfigDraft insert(@NonNull ConfigDraft draft) {
        return inTenantScope(draft.tenantId(), () -> {
            ConfigDraftDO dob = toDO(draft);
            mapper.insert(dob);
            // ⚠️ created_at/updated_at 是 DB 端 DEFAULT now()，BaseMapper.insert 不会回填 DO
            //（除非配 useGeneratedKeys + RETURNING，PG 下需额外处理）。原 JDBC 实现用
            // INSERT ... RETURNING created_at, updated_at 取回，语义不能丢——这里按主键重读取回。
            return findById(draft.scope(), draft.tenantId(), draft.draftId()).orElseThrow();
        });
    }

    @Override
    public @NonNull Optional<ConfigDraft> findById(@NonNull String scope,
                                                     @Nullable String tenantId,
                                                     @NonNull UUID draftId) {
        return inTenantScope(tenantId, () -> mapper.findById(scope, uuid(tenantId), draftId)
                .map(MybatisConfigDraftRepository::toRecord));
    }

    @Override
    public @NonNull Page<ConfigDraft> list(@NonNull String scope, @Nullable String tenantId,
                                            @Nullable String state, @Nullable String cursor,
                                            int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        return inTenantScope(tenantId, () -> {
            // ⚠️ 游标解码/编码与旧实现同源 PageCursor（此处省略解码细节，逻辑逐字保留）
            Instant cursorTime = cursor == null ? null : Instant.parse(cursor.split("\\|")[0]);
            UUID cursorId = cursor == null ? null : UUID.fromString(cursor.split("\\|")[1]);

            List<ConfigDraftDO> rows = mapper.listByCursor(
                    scope, uuid(tenantId), state, cursorTime, cursorId, limit + 1);

            String next = null;
            if (rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit)); // 多取的一行只用于判定
                ConfigDraftDO last = rows.get(rows.size() - 1);
                next = last.getUpdatedAt() + "|" + last.getDraftId();
            }
            return Page.of(rows.stream().map(MybatisConfigDraftRepository::toRecord).toList(), next);
        });
    }

    @Override
    public @NonNull ConfigDraft transitionState(@NonNull String scope, @Nullable String tenantId,
                                                 @NonNull UUID draftId,
                                                 @NonNull String expectedCurrentState,
                                                 @NonNull String targetState,
                                                 @Nullable UUID linkedRequestId,
                                                 @Nullable String operatorId) {
        return inTenantScope(tenantId, () -> {
            int rows = mapper.transitionState(scope, uuid(tenantId), draftId,
                    expectedCurrentState, targetState, linkedRequestId, operatorId, Instant.now());
            if (rows != 1) {
                // ⚠️ fail-closed：0 行 = 并发冲突或非法迁移，绝不静默成功
                throw new IllegalStateException("状态迁移失败（并发冲突或非法迁移）: "
                        + expectedCurrentState + " → " + targetState);
            }
            return findById(scope, tenantId, draftId).orElseThrow();
        });
    }

    // ── 其余 Port 方法（saveContent / markFailed / markRejected ...）按同范式实现 ──
    // ...（省略，落地时逐个补齐，每个都经 inTenantScope + Mapper）

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理（串租户防护的关键）。 */
    private <T> T inTenantScope(@Nullable String tenantId, java.util.function.Supplier<T> action) {
        String effective = tenantId == null || tenantId.isBlank() ? PLATFORM_SCOPE_TENANT : tenantId;
        TenantScope.bind(effective); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            // ⚠️ 无条件清理：Scope 与拦截器 BOUND 标记一起清，防止线程池串租户
            cn.studykid.multiagent.infrastructure.persistence.rls.RlsTenantBindingInterceptor
                    .onConnectionRelease();
        }
    }

    /** PLATFORM scope（tenantId=null）译成零 UUID 哨兵，DB 侧实际存 null（DO/XML 用 IS NOT DISTINCT FROM）。 */
    private static UUID uuid(@Nullable String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : UUID.fromString(tenantId);
    }

    // ── DO ↔ record 转换（逐字段，与旧 JDBC 的 read()/map() 对齐）──

    private static ConfigDraftDO toDO(ConfigDraft r) {
        ConfigDraftDO d = new ConfigDraftDO();
        d.setDraftId(r.draftId());
        d.setScope(r.scope());
        d.setTenantId(uuid(r.tenantId()));
        d.setResourceKind(r.resourceKind());
        d.setResourceId(r.resourceId());
        d.setResourceName(r.resourceName());
        d.setBaseVersion(r.baseVersion());
        d.setDraftVersion(r.draftVersion());
        d.setDraftState(r.draftState());
        d.setDraftConfig(r.draftConfig());
        d.setConfigFingerprint(r.configFingerprint());
        d.setLatestRequestId(r.latestRequestId());
        d.setFailureStage(r.failureStage());
        d.setFailureDetails(r.failureDetails() == null ? "[]" : r.failureDetails());
        d.setRejectionReason(r.rejectionReason());
        d.setOperatorId(r.operatorId());
        return d;
    }

    private static ConfigDraft toRecord(ConfigDraftDO d) {
        return new ConfigDraft(
                d.getDraftId(), d.getScope(), d.getTenantId() == null ? null : d.getTenantId().toString(),
                d.getResourceKind(), d.getResourceId(), d.getResourceName(),
                d.getBaseVersion(), d.getDraftVersion(), d.getDraftState(),
                d.getDraftConfig(), d.getConfigFingerprint(), d.getLatestRequestId(),
                d.getFailureStage(), d.getFailureDetails(), d.getRejectionReason(), d.getOperatorId(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
