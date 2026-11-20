package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.LifecycleStateRow;

/**
 * 生命周期状态迁移 / 读取 Mapper（对应 {@code JdbcConfigLifecycleRepository}）。
 *
 * <p>⚠️ <b>刻意不继承 {@code BaseMapper}</b>：本 Port 跨 6 张表（3 张平台级 + 3 张租户级），
 * 且结果不是单表实体（状态读只取 1~2 列），没有「一张表一个 DO」的对应关系。
 * 每条语句与旧 JDBC 的 {@code switch} 分支一一对应，SQL 逐字搬迁
 * （仅 {@code ?} -> {@code #{param}}，{@code ::cap_lifecycle_state} / {@code ::uuid} cast 全部保留）。
 *
 * <p>⚠️ <b>对象类型分「平台级」与「租户级」两族</b>（第二十五批 A5）：
 * <ul>
 *   <li><b>平台级</b>（tool / skill / mcp_server）：无 {@code tenant_id}，按 {@code id} 寻址；</li>
 *   <li><b>租户级</b>（agent / result_contract / routing_policy）：按 {@code (tenant_id, 业务 id, version)}
 *       寻址，且都是 FORCE RLS 表 —— 必须绑定租户上下文，否则读写恒为 0 行
 *       （迁移成功了但什么都没发生）。</li>
 * </ul>
 *
 * <p>⚠️ <b>{@code contract_id} / {@code policy_id} 是可读字符串，绝不能写 {@code ::uuid}</b>
 * （V23 / V24 的教训）：非 UUID 形式的 id 会让整条语句报错，而那正是
 * 「登记失败」与「类型写错」两种现场混在一起的地方。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描：适配层调用前必须先 {@code TenantScope.bind(...)}，
 * 否则拦截器 fail-closed 抛异常（平台级表虽无 RLS，也照样绑定，与旧实现同口径）。
 */
public interface ConfigLifecycleMapper {

    // ── transition：CAS 条件 UPDATE（expectedState 在 WHERE 里才算并发安全）──

    /** 平台级：{@code cap_tool_def}，按 id + version 寻址。 */
    int transitionTool(@Param("objectId") String objectId,
                       @Param("version") int version,
                       @Param("expectedState") String expectedState,
                       @Param("targetState") String targetState);

    /** 平台级：{@code cap_skill_def}，按 id + version 寻址。 */
    int transitionSkill(@Param("objectId") String objectId,
                        @Param("version") int version,
                        @Param("expectedState") String expectedState,
                        @Param("targetState") String targetState);

    /** 平台级：{@code cap_mcp_server_def}，按 id + version 寻址。 */
    int transitionMcpServer(@Param("objectId") String objectId,
                            @Param("version") int version,
                            @Param("expectedState") String expectedState,
                            @Param("targetState") String targetState);

    /** 租户级：{@code cap_agent_def}，按 {@code agent_id::uuid} + version 寻址。 */
    int transitionAgent(@Param("objectId") String objectId,
                        @Param("version") int version,
                        @Param("expectedState") String expectedState,
                        @Param("targetState") String targetState);

    /** 租户级：{@code cap_result_contract}，按 {@code (tenant_id::uuid, contract_id, version)} 寻址。 */
    int transitionResultContract(@Param("tenantId") @Nullable String tenantId,
                                 @Param("objectId") String objectId,
                                 @Param("version") int version,
                                 @Param("expectedState") String expectedState,
                                 @Param("targetState") String targetState);

    /** 租户级：{@code cap_routing_policy}，按 {@code (tenant_id::uuid, policy_id, version)} 寻址。 */
    int transitionRoutingPolicy(@Param("tenantId") @Nullable String tenantId,
                                @Param("objectId") String objectId,
                                @Param("version") int version,
                                @Param("expectedState") String expectedState,
                                @Param("targetState") String targetState);

    // ── stateOf：状态读取 ──
    // 平台级/agent 分支只取 lifecycle_state（无 tenant_id 列可染色），返回 null = 无此版本行；
    // 租户级 2 分支额外取 tenant_id::text，供影子钩子逐行染色（与旧实现读的行归属一致）。

    /** 平台级：{@code SELECT lifecycle_state FROM cap_tool_def WHERE id = ? AND version = ?}。 */
    @Nullable
    String selectToolState(@Param("objectId") String objectId,
                           @Param("version") int version);

    /** 平台级：{@code SELECT lifecycle_state FROM cap_skill_def WHERE id = ? AND version = ?}。 */
    @Nullable
    String selectSkillState(@Param("objectId") String objectId,
                            @Param("version") int version);

    /** 平台级：{@code SELECT lifecycle_state FROM cap_mcp_server_def WHERE id = ? AND version = ?}。 */
    @Nullable
    String selectMcpServerState(@Param("objectId") String objectId,
                                @Param("version") int version);

    /** 租户级：{@code SELECT lifecycle_state FROM cap_agent_def WHERE agent_id = ?::uuid AND version = ?}。 */
    @Nullable
    String selectAgentState(@Param("objectId") String objectId,
                            @Param("version") int version);

    /** 租户级：{@code SELECT tenant_id::text, lifecycle_state FROM cap_result_contract ...}。 */
    @Nullable
    LifecycleStateRow selectResultContractState(@Param("tenantId") @Nullable String tenantId,
                                                @Param("objectId") String objectId,
                                                @Param("version") int version);

    /** 租户级：{@code SELECT tenant_id::text, lifecycle_state FROM cap_routing_policy ...}。 */
    @Nullable
    LifecycleStateRow selectRoutingPolicyState(@Param("tenantId") @Nullable String tenantId,
                                               @Param("objectId") String objectId,
                                               @Param("version") int version);
}
