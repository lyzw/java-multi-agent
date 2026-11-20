package cn.studykid.multiagent.infrastructure.persistence;

import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.application.cap.ContextPolicyReader;
import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.mapper.AgentManageMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.AgentIdDO;
import cn.studykid.multiagent.infrastructure.persistence.model.AgentVersionCreatedDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.AuditPort;

/**
 * {@link AgentManagePort} 的 MyBatis-Plus 实现（对应 {@code JdbcAgentManageRepository}）。
 *
 * <p>Port 签名零改动；五条 SQL 逐字搬迁到 {@code AgentManageMapper.xml}，
 * 仅 {@code ?} → {@code #{param}}。版本号 {@code max+1} 子查询、
 * {@code RETURNING}、{@code ::uuid} / {@code ::jsonb} cast、
 * 执行快照与版本行同事务、审计在提交后 fail-closed 全部保留。
 *
 * <p>⚠️ <b>definition 是不可变快照</b>（FR-002）：本类<b>不提供</b>编辑已发布版本的方法。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接；
 * 全局 Agent（{@code tenantId == null}）用零 UUID 哨兵绑定上下文。
 *
 * <p>⚠️ <b>异常口径</b>：MyBatis 把 {@link java.sql.SQLException} 包进
 * {@code PersistenceException}，故唯一约束冲突（{@code 23505}）与非法 JSONB
 * （{@code 22P02}）沿 cause 链识别，转译成与旧实现相同的异常类型与消息。
 */
public class MybatisAgentManageRepository implements AgentManagePort {

    private static final Logger log = LoggerFactory.getLogger(MybatisAgentManageRepository.class);

    /** Agent 名称长度上限（自由文本，过长是异常信号）。 */
    private static final int MAX_NAME_LENGTH = 200;

    /** Postgres 唯一约束冲突（unique_violation）。 */
    private static final String UNIQUE_VIOLATION = "23505";

    /** Postgres 非法文本表示（如非法 JSONB）。 */
    private static final String INVALID_TEXT_REPRESENTATION = "22P02";

    /** PLATFORM 作用域（全局 Agent，无绑定租户）的哨兵上下文标识。 */
    private static final String GLOBAL_CONTEXT_TENANT =
            "00000000-0000-0000-0000-000000000000";

    /**
     * DEC-9 运行期容量配置快照（与 {@code application.yml} 的 {@code agent.run.*} 默认值一致）。
     *
     * <p>⚠️ 值来源是<b>平台默认档</b>，不是「该租户当时的生效值」——
     * 运行期当前不消费该列（读路径未接线），故此处取平台默认档作为形状基准。
     */
    private static final String RUN_CONFIG_DEFAULTS =
            "{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,"
                    + "\"approvalRequired\":\"OPTIONAL\"}";

    private final AgentManageMapper mapper;
    private final DataSource dataSource;
    private final AuditPort audit;

    // ── 影子态接入（WP-M1-2，默认不启用零行为变更）────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

    public MybatisAgentManageRepository(@NonNull AgentManageMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource,
                                        @NonNull AuditPort audit) {
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.audit = audit;
    }

    /**
     * 影子态装配入口：容器里存在 {@link AuthzShadowBinding} 即开启（P1 影子期），
     * 不存在即完全关闭（零行为变更）。
     */
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
    public @NonNull AgentItem createAgent(@Nullable String tenantId,
                                          @NonNull String name,
                                          @Nullable String description,
                                          @NonNull String creatorId)
            throws AgentManagePort.DuplicateAgentNameException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Agent 名称超长（>" + MAX_NAME_LENGTH + "）: " + name.length());
        }
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        //    tenantId=null（全局 Agent 未绑定）时无租户主体可比对，跳过。
        if (tenantId != null) {
            shadowCompare(tenantId);
        }
        // agent 表是全局实体、无 RLS；tenantId=null 时用零 UUID 哨兵绑定上下文即可。
        String contextTenant = tenantId == null ? GLOBAL_CONTEXT_TENANT : tenantId;
        String agentId;
        try {
            agentId = inTenantScope(contextTenant, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(contextTenant), conn -> {
                        AgentIdDO created = mapper.insertAgent(name, description)
                                .orElseThrow(() -> new IllegalStateException("Agent 插入未返回 ID"));
                        // 全局 Agent 有初始租户时立即建立绑定；tenantId=null（运营尚未指定）时
                        // 只建全局行，后续由「分配租户」动作建立绑定。
                        if (tenantId != null) {
                            mapper.insertBinding(tenantId, created.getAgentId());
                        }
                        return created.getAgentId();
                    }));
        } catch (RuntimeException e) {
            throw createFailure(e, name);
        }
        // ⚠️ 审计明细<b>不</b>放 name：name 是用户自由文本，PIPL 敏感信息扫描（fail-closed）
        //     会拒写含 UUID/数字串的名称，进而让「创建 Agent」整条失败。
        //     审计的 R-1 可追溯性由 actor+action+resourceId(agentId)+时间戳满足。
        // 审计 tenant_id 是 NOT NULL 且受 RLS 约束：无租户时用零 UUID 哨兵
        // （与平台域/决策日志同口径），保证 R-1 可追溯性不断链。
        // ⚠️ 审计在提交<b>之后</b>执行（与原实现同序）：审计失败即抛（fail-closed）。
        audit.recordRequired(contextTenant, creatorId,
                AuditPort.Actions.AGENT_CREATE, "agent", agentId,
                Map.of());
        // ⚠️ 新建 Agent 尚无版本：versionCount=0 / latestVersionNumber=0 / 无 definition
        return new AgentItem(agentId, name, description, 0, 0, null, null);
    }

    @Override
    public @NonNull AgentVersionItem publishVersion(
            @NonNull String tenantId,
            @NonNull String agentId,
            @NonNull String definition,
            @NonNull String publisherId) {
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("发布版本必须包含 definition（JSON）");
        }
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        shadowCompare(tenantId);
        AgentVersionItem item;
        try {
            item = inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                    dataSource, context(tenantId), conn -> {
                        // 1) 校验 agent 存在且对该租户可见（绑定表决定可见性）
                        if (mapper.agentExists(tenantId, agentId) == null) {
                            throw new AgentQueryPort.AgentNotFoundException(agentId);
                        }
                        // 2) 插入下一版本：版本号 = 该 Agent 当前最大版本号 + 1（全局命名空间）
                        AgentVersionCreatedDO row = mapper.insertVersion(agentId, definition)
                                .orElseThrow(() -> new IllegalStateException("版本插入未返回 ID"));
                        // 3) 同事务创建执行快照（**必须有**）：JdbcExecutionSnapshotResolver 的
                        //    解析 SQL 是 agent JOIN agent_version JOIN execution_snapshot 的内连接，
                        //    没有快照 = 该 Agent 永远不可运行（404 AGENT_SNAPSHOT_UNAVAILABLE）。
                        //    快照与版本是同一个发布事实的两面，分开提交会出现
                        //    「版本已发布但无快照」的中间态。
                        createExecutionSnapshot(row.getVersionId(), row.getStoredDefinition());
                        return new AgentVersionItem(row.getVersionId(), row.getVersionNumber(),
                                row.getStoredDefinition(), row.getPublishedAt(),
                                row.getCreatedAt());
                    }));
        } catch (RuntimeException e) {
            throw publishFailure(e, agentId);
        }
        // ⚠️ 审计在提交之后执行（与原实现同序）：审计失败即抛（fail-closed）。
        audit.recordRequired(tenantId, publisherId,
                AuditPort.Actions.AGENT_PUBLISH, "agent", agentId,
                Map.of("versionNumber", item.versionNumber()));
        return item;
    }

    /**
     * 为已发布版本创建执行快照（与版本行同事务）。
     *
     * <p>⚠️ <b>内容口径</b>（与原实现逐字一致）：
     * {@code agent_config} = 该版本落库后的 definition；
     * {@code prompt_config} / {@code tool_contracts} = {@code {}}（尚无权威模型）；
     * {@code run_config} = DEC-9 平台默认档；
     * {@code context_policy} 从 definition 的 {@code generationParams.context} 派生，
     * 缺失 / 非对象 / 不可解析一律 {@code {}}（回落默认，不是关闭）。
     * 策略校验与序列化全部委托 {@link ContextPolicyReader}，禁止本仓储另写归一化。
     */
    private void createExecutionSnapshot(@Nullable String versionId,
                                         @Nullable String definition) {
        // ⚠️ 旧直发通道的 definition 是自由 schema；从其中的 generationParams.context
        //    派生规范快照。与 CAP 通道共用 ContextPolicyReader 的归一化与校验。
        String contextPolicy = ContextPolicyReader.snapshotFromAgentConfig(definition);
        mapper.insertSnapshot(versionId,
                definition == null || definition.isBlank() ? "{}" : definition,
                RUN_CONFIG_DEFAULTS, contextPolicy);
    }

    /** 创建失败转译：唯一约束冲突 → 重名（409）；其余 SQL 失败 → 原消息。 */
    private RuntimeException createFailure(RuntimeException e, String name) {
        if (e instanceof AgentManagePort.DuplicateAgentNameException) {
            return e;
        }
        if (UNIQUE_VIOLATION.equals(sqlStateOf(e))) {
            // ⚠️ agent_name_key（全局 name）唯一约束冲突 → 重名（409，非 400）
            return new AgentManagePort.DuplicateAgentNameException(name);
        }
        if (isSqlFailure(e)) {
            return new IllegalStateException("创建 Agent 失败", e);
        }
        return e;
    }

    /** 发布失败转译：23505 → 并发版本冲突（409）；22P02 → 非法 JSON（400）。 */
    private RuntimeException publishFailure(RuntimeException e, String agentId) {
        if (e instanceof AgentQueryPort.AgentNotFoundException
                || e instanceof AgentManagePort.VersionConflictException) {
            return e;
        }
        String state = sqlStateOf(e);
        if (UNIQUE_VIOLATION.equals(state)) {
            // ⚠️ (agent_id, version_number) 唯一约束冲突 → 并发版本竞态（409）
            return new AgentManagePort.VersionConflictException(
                    "版本号并发冲突，请刷新后重试（agent=" + agentId + "）");
        }
        if (INVALID_TEXT_REPRESENTATION.equals(state)) {
            // ⚠️ definition 非法 JSON（PG 拒收 JSONB）= 请求体不合法（400）
            return new IllegalArgumentException(
                    "definition 不是合法 JSON: " + sqlMessageOf(e));
        }
        if (isSqlFailure(e)) {
            return new IllegalStateException("发布 Agent 版本失败", e);
        }
        return e;
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(@NonNull String tenantId, Supplier<T> action) {
        TenantScope.bind(tenantId); // 来自认证上下文的 tenantId，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    /** 系统身份仅用于绑定 RLS 上下文，不携带任何角色权限。 */
    private static TenantContext context(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    /** MyBatis 把 SQLException 包进 PersistenceException；沿 cause 链识别以免误伤业务异常。 */
    private static boolean isSqlFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }

    /** 取 cause 链中第一个 SQLException 的 SQLState（唯一约束 / 非法 JSON 需要它）。 */
    private static @Nullable String sqlStateOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se) {
                return se.getSQLState();
            }
        }
        return null;
    }

    /** 取 cause 链中第一个 SQLException 的 message（保持原实现的错误文案）。 */
    private static @Nullable String sqlMessageOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se) {
                return se.getMessage();
            }
        }
        return e.getMessage();
    }

    // ── 影子辅助（守卫要求：声明与调用分离，入口真调用）────────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次绑定租户（只记日志）。
     * 写主导仓无行租户染色点；全 SQL 显式 {@code tenant_id} / 绑定谓词 ⇒
     * hasTenantPredicate=true。
     */
    void shadowCompare(@NonNull String boundTenantId) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "agent",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
        if (log.isTraceEnabled()) {
            log.trace("影子比对（写主导仓，无染色点）: tenant={}", boundTenantId);
        }
    }
}
