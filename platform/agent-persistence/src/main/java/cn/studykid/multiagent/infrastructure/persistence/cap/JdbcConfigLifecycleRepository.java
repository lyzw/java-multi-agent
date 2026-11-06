package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort;

/**
 * {@link ConfigLifecyclePort} 的 PostgreSQL 实现（§6.6 / 06 §3 · W-PERM-P5）。
 *
 * <p>⚠️ <b>CAS 前置条件是「期望原状态」</b>：
 * {@code WHERE ... AND lifecycle_state = ?}。
 * 没有它，两个并发操作（一个想发布、一个想停用）会各自读到同一个旧状态，
 * 后写者直接覆盖先写者 —— 状态迁移就成了「谁最后写谁赢」，
 * 而生命周期迁移恰恰需要「先到先得」或「显式冲突」。
 *
 * <p>⚠️ <b>PUBLISHED → DEPRECATED 能穿过不可变触发器</b>：
 * V15 的 {@code forbid_published_def_mutation} 在 V17 里被扩展为
 * 「允许纯元数据的停用迁移」（内容一字未改）。
 * 若不放行，06 §3.4「被引用对象禁止物理删除 → 改 DEPRECATED」在数据层无法表达。
 *
 * <p>⚠️ <b>本类不写 {@code published_at}</b>：
 * 「已发布」由发布动作决定（发布服务在发布事务里插入带 published_at 的版本行），
 * 生命周期迁移只动状态列。
 *
 * <p>⚠️ <b>对象类型分「平台级」与「租户级」两族</b>（第二十五批 · A5）：
 * <ul>
 *   <li><b>平台级</b>（tool / skill / mcp_server）：无 {@code tenant_id}，按 {@code id} 寻址；</li>
 *   <li><b>租户级</b>（agent / result_contract / routing_policy）：
 *       按 {@code (tenant_id, 业务 id, version)} 寻址，
 *       且都是 FORCE RLS 表 —— <b>必须</b>绑定租户上下文，
 *       否则读写恒为 0 行（迁移成功了但什么都没发生）。</li>
 * </ul>
 * 混用两族的寻址方式会让「按 id 更新」打到别的租户的行上 ——
 * 那是跨租户结构泄漏，而它<b>不报错</b>，只是静默更新错对象。
 */
public class JdbcConfigLifecycleRepository implements ConfigLifecyclePort {

    private static final String TYPE_TOOL = "tool";
    private static final String TYPE_SKILL = "skill";
    private static final String TYPE_MCP_SERVER = "mcp_server";
    private static final String TYPE_AGENT = "agent";
    /** 输出契约（租户级 · 多级 Agent v1.1 · 第二十五批 A5）。 */
    private static final String TYPE_RESULT_CONTRACT = "result_contract";
    /** 路由策略（租户级 · 多级 Agent v1.1 第十四批 · 第二十五批 A5）。 */
    private static final String TYPE_ROUTING_POLICY = "routing_policy";

    private final DataSource dataSource;

    public JdbcConfigLifecycleRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean transition(@NonNull String type,
                              @Nullable String tenantId,
                              @NonNull String objectId,
                              int version,
                              @NonNull ConfigLifecycleState expectedFrom,
                              @NonNull ConfigLifecycleState to) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(type, tenantId),
                conn -> {
                    String sql = switch (type) {
                        case TYPE_TOOL -> "UPDATE cap_tool_def SET lifecycle_state = ?::cap_lifecycle_state"
                                + " WHERE id = ? AND version = ? AND lifecycle_state = ?::cap_lifecycle_state";
                        case TYPE_SKILL -> "UPDATE cap_skill_def SET lifecycle_state = ?::cap_lifecycle_state"
                                + " WHERE id = ? AND version = ? AND lifecycle_state = ?::cap_lifecycle_state";
                        case TYPE_MCP_SERVER -> "UPDATE cap_mcp_server_def"
                                + " SET lifecycle_state = ?::cap_lifecycle_state"
                                + " WHERE id = ? AND version = ? AND lifecycle_state = ?::cap_lifecycle_state";
                        case TYPE_AGENT -> "UPDATE cap_agent_def"
                                + " SET lifecycle_state = ?::cap_lifecycle_state"
                                + " WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND version = ?"
                                + " AND lifecycle_state = ?::cap_lifecycle_state";
                        // ⚠️ 契约 / 策略的 id 是**可读字符串**（V23 / V24 的教训），
                        //    绝不能写 ?::uuid —— 非 UUID 形式的 id 会让整条语句报错，
                        //    而那正是「登记失败」与「类型写错」两种现场混在一起的地方。
                        case TYPE_RESULT_CONTRACT -> "UPDATE cap_result_contract"
                                + " SET lifecycle_state = ?::cap_lifecycle_state"
                                + " WHERE tenant_id = ?::uuid AND contract_id = ? AND version = ?"
                                + " AND lifecycle_state = ?::cap_lifecycle_state";
                        case TYPE_ROUTING_POLICY -> "UPDATE cap_routing_policy"
                                + " SET lifecycle_state = ?::cap_lifecycle_state"
                                + " WHERE tenant_id = ?::uuid AND policy_id = ? AND version = ?"
                                + " AND lifecycle_state = ?::cap_lifecycle_state";
                        default -> throw new IllegalArgumentException("未知对象类型: " + type);
                    };
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, to.name());
                        if (isTenantScoped(type)) {
                            ps.setString(2, tenantId);
                            ps.setString(3, objectId);
                            ps.setInt(4, version);
                            ps.setString(5, expectedFrom.name());
                        } else {
                            ps.setString(2, objectId);
                            ps.setInt(3, version);
                            ps.setString(4, expectedFrom.name());
                        }
                        return ps.executeUpdate() == 1;
                    }
                });
    }

    @Override
    @NonNull
    public Optional<ConfigLifecycleState> stateOf(@NonNull String type,
                                                   @Nullable String tenantId,
                                                   @NonNull String objectId,
                                                   int version) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, context(type, tenantId),
                conn -> {
                    String sql = switch (type) {
                        case TYPE_TOOL -> "SELECT lifecycle_state FROM cap_tool_def"
                                + " WHERE id = ? AND version = ?";
                        case TYPE_SKILL -> "SELECT lifecycle_state FROM cap_skill_def"
                                + " WHERE id = ? AND version = ?";
                        case TYPE_MCP_SERVER -> "SELECT lifecycle_state FROM cap_mcp_server_def"
                                + " WHERE id = ? AND version = ?";
                        case TYPE_AGENT -> "SELECT lifecycle_state FROM cap_agent_def"
                                + " WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND version = ?";
                        case TYPE_RESULT_CONTRACT -> "SELECT lifecycle_state FROM cap_result_contract"
                                + " WHERE tenant_id = ?::uuid AND contract_id = ? AND version = ?";
                        case TYPE_ROUTING_POLICY -> "SELECT lifecycle_state FROM cap_routing_policy"
                                + " WHERE tenant_id = ?::uuid AND policy_id = ? AND version = ?";
                        default -> throw new IllegalArgumentException("未知对象类型: " + type);
                    };
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int index = 1;
                        if (isTenantScoped(type)) {
                            ps.setString(index++, tenantId);
                            ps.setString(index++, objectId);
                            ps.setInt(index++, version);
                        } else {
                            ps.setString(index++, objectId);
                            ps.setInt(index++, version);
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.empty();
                            }
                            return Optional.of(ConfigLifecycleState.valueOf(rs.getString(1)));
                        }
                    } catch (SQLException e) {
                        // ⚠️ 状态读不出来**不得**被当成「没有状态」：
                        //    「读不到」与「不是 DEPRECATED」是两个事实，
                        //    混为一谈会让「停用」防线静默失效。
                        //    这里让异常向上抛（fail-closed），由上层显式处理。
                        throw new IllegalStateException(
                                "读取生命周期状态失败: type=" + type + ", object=" + objectId
                                        + ", version=" + version, e);
                    }
                });
    }

    /**
     * 该对象类型是否<b>按租户寻址</b>（租户级对象）。
     *
     * <p>⚠️ 租户级对象都是 FORCE RLS 表：寻址方式与 RLS 上下文<b>必须</b>同时对上，
     * 否则「更新 0 行」会被读成「状态没变」——
     * 那是一个<b>不报错</b>的失败：调用方收到「迁移未生效」，
     * 却不知道真正的原因是「租户上下文没绑」。
     */
    private static boolean isTenantScoped(@NonNull String type) {
        return TYPE_AGENT.equals(type)
                || TYPE_RESULT_CONTRACT.equals(type)
                || TYPE_ROUTING_POLICY.equals(type);
    }

    /**
     * 构造租户上下文。
     *
     * <p>⚠️ 租户级表（{@code cap_agent_def} / {@code cap_result_contract} /
     * {@code cap_routing_policy}）是 FORCE RLS 的租户表 ——
     * 不绑定租户上下文时读写恒为 0 行。
     * 平台级三张表无 RLS，但仍绑定上下文，
     * 以免同一事务内访问带 RLS 的表时被拒绝。
     */
    private static TenantContext context(String type, @Nullable String tenantId) {
        if (isTenantScoped(type) && tenantId != null) {
            return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
        }
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    /** PLATFORM 作用域的租户哨兵：零 UUID。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";
}
