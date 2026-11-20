package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.exceptions.PersistenceException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.RunEventAppendMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunEventPort;

/**
 * Run 事件追加的 MyBatis-Plus 实现（对应 {@code JdbcRunEventAppendRepository}）。
 *
 * <p>Port 签名零改动；三条 SQL 逐字搬迁到 {@code RunEventAppendMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * 锁 run 行 → 算 MAX+1 → 插入事件三步在同一事务同一连接完成（RLS 绑定与锁都依赖这一点）。
 * 载荷白名单 / NaN 拒绝 / 影子比对语义与原实现完全一致。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：Mapper 调用前用 {@code tenantId} 绑定 {@link TenantScope}（try/finally 无条件清理），
 * 并经 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * 该工具外层有连接则复用、否则自开并绑定租户（内部仍走 {@link TenantConnectionLease}），
 * 与旧 {@code tenantConnection()} 的连接模型逐字对应。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisRunEventAppendRepository implements RunEventPort {

    /**
     * JSON 序列化器（Jackson 3）。
     *
     * <p>⚠️ 用它而不是手工拼字符串：事件载荷落进 PG 的 {@code jsonb} 列，
     * 裸控制字符会被 PG 拒绝 → 事件追加失败 → 整条 Run 的时间线丢失。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunEventAppendMapper mapper;
    private final DataSource dataSource;

    // ── 影子态接入（与旧实现同口径，默认不启用零行为变更）────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    public MybatisRunEventAppendRepository(@NonNull RunEventAppendMapper mapper,
                                           @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    /** 影子态装配入口：容器里存在 {@link AuthzShadowBinding} 即开启（P1 影子期），不存在即完全关闭。 */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    @Override
    public void append(@NonNull String tenantId,
                       @NonNull String runId,
                       @NonNull String eventType,
                       @NonNull Map<String, Object> payload) {
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        shadowCompare(tenantId);
        try {
            inTenantScope(tenantId, () -> {
                TransactionalConnectionHolder.inTransaction(
                        dataSource, systemContext(tenantId), conn -> {
                            // ⚠️ 先锁 run 行：没有它，同一 Run 的并发追加会算出相同序号。
                            if (mapper.lockRun(tenantId, runId).isEmpty()) {
                                // ⚠️ Run 不存在（已被清理？）→ 不写事件。
                                //    写入会撞外键，且事件对已消失的 Run 无意义。
                                throw new IllegalStateException("Run 不存在，拒绝追加事件: " + runId);
                            }
                            Long next = mapper.nextSequence(tenantId, runId);
                            long sequenceNo = next == null ? 1L : next;
                            mapper.insertEvent(tenantId, runId, sequenceNo, eventType,
                                    payloadJson(payload));
                            return null;
                        });
                return null;
            });
        } catch (PersistenceException e) {
            // ⚠️ 与原实现 catch(SQLException) 同口径：底层 SQL 失败显式报错。
            //    MyBatis 把 SQLException 包成 PersistenceException，故在此处转译。
            //    业务异常（Run 不存在 / 载荷非法）原样透传，不被包装。
            throw new IllegalStateException("追加 Run 事件失败", e);
        }
    }

    /**
     * 载荷转 JSON。
     *
     * <p>⚠️ <b>只允许标量</b>：与 SSE 白名单一致（P3-D10 §5.2 S-6）。
     * 非标量值（Map/List）<b>直接拒绝</b>而不是静默丢弃。
     *
     * <p>⚠️ <b>序列化走 Jackson，不手工拼字符串</b>：裸控制字符（\b \f 与 U+0000～U+001F）
     * 会被 PG 拒绝（RFC 8259 要求必须转义）→ 事件追加失败 → 整条 Run 时间线丢失。
     */
    private static String payloadJson(Map<String, Object> payload) {
        ObjectNode root = JSON.createObjectNode();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof String str) {
                root.put(e.getKey(), str);
            } else if (v instanceof Boolean bool) {
                root.put(e.getKey(), bool);
            } else if (v instanceof Number number) {
                // ⚠️ NaN / Infinity 不是合法 JSON（RFC 8259 无此字面量），
                //    PG 的 jsonb 也会拒 → 明确拒绝而不是产出一个坏载荷。
                if (number instanceof Double d && !Double.isFinite(d)
                        || number instanceof Float f && !Float.isFinite(f)) {
                    throw new IllegalArgumentException(
                            "事件载荷的数值必须是有限值（NaN/Infinity 不是合法 JSON）: "
                                    + e.getKey() + "=" + number);
                }
                root.set(e.getKey(), JSON.valueToTree(number));
            } else {
                throw new IllegalArgumentException(
                        "事件载荷只允许标量（String/Number/Boolean），实际: " + e.getKey()
                                + "=" + v.getClass().getSimpleName());
            }
        }
        return JSON.writeValueAsString(root);
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, java.util.function.Supplier<T> action) {
        TenantScope.bind(tenantId);
        try {
            return action.get();
        } finally {
            TenantScope.clear();
        }
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限（与原实现逐字一致）。 */
    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    // ── 影子辅助（守卫要求：声明与调用分离，入口真调用）────────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次绑定租户（只记日志）。
     * 写主导仓无行租户染色点；全 SQL 显式 tenant_id 谓词 ⇒ hasTenantPredicate=true。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "run_event",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
    }
}
