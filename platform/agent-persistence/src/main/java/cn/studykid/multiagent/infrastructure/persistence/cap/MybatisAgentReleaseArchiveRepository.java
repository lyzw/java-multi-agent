package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.Optional;
import java.util.Set;
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

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.AgentReleaseArchiveMapper;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.AgentReleaseArchivePort;

/**
 * {@link AgentReleaseArchivePort} 的 MyBatis-Plus 实现（对应
 * {@code JdbcAgentReleaseArchiveRepository}）。
 *
 * <p>Port 签名零改动；四条 SQL 逐字搬迁到 {@code AgentReleaseArchiveMapper.xml}，
 * 仅 {@code ?} → {@code #{param}}。{@code ON CONFLICT DO NOTHING RETURNING version_id}、
 * {@code ::jsonb} cast、快照存在性前置判定、全局 Agent 主体存在性 fail-closed 全部保留。
 *
 * <p>⚠️ <b>本类写的是归档，不是真源</b>：{@code agent_version.definition} 由
 * {@code cap_agent_def} 派生，运行期不读它 —— 存在的唯一理由是维持
 * {@code run → execution_snapshot → agent_version} 的外键链。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 * 平台运维通道的归档 Bean（{@code platformOpsAgentReleaseArchivePort}）仍走原生 JDBC，
 * 不在本次替换范围内。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（bind/clear try-finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository("agentReleaseArchivePort")
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisAgentReleaseArchiveRepository implements AgentReleaseArchivePort {

    private static final Logger log = LoggerFactory.getLogger(
            MybatisAgentReleaseArchiveRepository.class);

    // ── 影子态接入（WP-M1-2，默认不启用零行为变更）────────────────────
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private volatile boolean shadowEnabled;
    private @NonNull String sentinelTenantId = "";

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

    /**
     * DEC-9 运行期容量配置快照（与 {@code application.yml} 的 {@code agent.run.*} 一致）。
     *
     * <p>⚠️ 与 {@code JdbcAgentManageRepository} 同口径：平台默认档，
     * 不是该租户当时的生效值（运行期当前不消费该列）。
     */
    private static final String RUN_CONFIG_DEFAULTS =
            "{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,\"maxSteps\":20,"
                    + "\"approvalRequired\":\"OPTIONAL\"}";

    private final AgentReleaseArchiveMapper mapper;
    private final DataSource dataSource;

    public MybatisAgentReleaseArchiveRepository(
            @NonNull AgentReleaseArchiveMapper mapper,
            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public boolean archiveRelease(@Nullable String tenantId,
                                  @NonNull String agentId,
                                  int version,
                                  @NonNull String definition,
                                  @Nullable String agentConfig,
                                  @NonNull String contextPolicy) {
        // ⚠️ 影子比对（P1 只记日志）：Gate 算出的 ScopeFilter vs 本次绑定租户。
        shadowCompare(tenantId);
        return inTenantScope(tenantId, () -> TransactionalConnectionHolder.inTransaction(
                dataSource, tenantContext(tenantId),
                conn -> doArchive(agentId, version, definition, agentConfig,
                        contextPolicy)));
    }

    private boolean doArchive(String agentId, int version,
                              String definition, @Nullable String agentConfig,
                              @NonNull String contextPolicy) {
        // ⚠️ **必须先有全局 agent 行**：agent_version 外键指向 agent(agent_id)。
        if (mapper.findAgent(agentId) == null) {
            throw new IllegalStateException(
                    "归档 Agent 发布失败：Agent 主体不存在（agent=" + agentId + "）。"
                            + "治理通道发布前必须先登记 Agent 主体。");
        }
        Optional<String> versionId = mapper.insertVersion(agentId, version, jsonOrEmpty(definition))
                .map(d -> d.getVersionId());
        boolean inserted = versionId.isPresent();
        if (!inserted) {
            log.debug("Agent 发布归档：版本行已存在，幂等补齐快照（agent={}, version={}）",
                    agentId, version);
            versionId = mapper.findVersionId(agentId, version).map(d -> d.getVersionId());
            if (versionId.isEmpty()) {
                throw new IllegalStateException(
                        "归档 Agent 发布失败：版本行插入被跳过但回查不到版本行"
                                + "（agent=" + agentId + ", version=" + version + "）—— 数据不一致，拒绝发布");
            }
        }
        insertSnapshotIfAbsent(versionId.get(), agentConfig, contextPolicy);
        return inserted;
    }

    private void insertSnapshotIfAbsent(String versionId,
                                        @Nullable String agentConfig,
                                        @NonNull String contextPolicy) {
        if (mapper.findSnapshot(versionId) != null) {
            return;
        }
        mapper.insertSnapshot(versionId, jsonOrEmpty(agentConfig),
                RUN_CONFIG_DEFAULTS, contextPolicy);
    }

    /** 与原实现同口径：null / 空白 → {@code {}}（JSONB 列不接受空串）。 */
    private static String jsonOrEmpty(@Nullable String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static final String GLOBAL_CONTEXT_TENANT =
            "00000000-0000-0000-0000-000000000000";

    private static TenantContext tenantContext(@Nullable String tenantId) {
        // PLATFORM 作用域发布全局 Agent 时 tenantId 为 null；归档表（agent_version /
        // execution_snapshot）已全局化、无 tenant_id 列，这里仅用哨兵绑定 RLS 上下文。
        return new TenantContext(tenantId == null ? GLOBAL_CONTEXT_TENANT : tenantId,
                "system", Set.of(), 0L);
    }

    /** 在租户作用域内执行：绑定 -> 执行 -> 无条件清理（try-finally）。 */
    private <T> T inTenantScope(@Nullable String tenantId, Supplier<T> action) {
        // PLATFORM 作用域（tenantId=null）用零 UUID 哨兵绑定，与原 tenantContext 同口径。
        TenantScope.bind(tenantId == null ? GLOBAL_CONTEXT_TENANT : tenantId);
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }

    // ── 影子辅助（守卫要求：声明与调用分离，入口真调用）────────────

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次绑定租户（只记日志）。
     * 写主导仓无行租户染色点；全 SQL 显式 {@code tenant_id} 谓词 ⇒ hasTenantPredicate=true。
     */
    void shadowCompare(@Nullable String boundTenantId) {
        // PLATFORM 作用域无租户主体可比对，跳过影子比对（与 JdbcConfigReleaseRepository 同口径）。
        if (boundTenantId == null) {
            return;
        }
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, "agent_version",
                SubjectContext.forTenant(boundTenantId), boundTenantId, true);
    }
}
