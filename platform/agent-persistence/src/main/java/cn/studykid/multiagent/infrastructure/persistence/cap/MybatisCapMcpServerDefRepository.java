package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapMcpServerDefMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapMcpServerDefDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;

/**
 * {@link CapMcpServerDefPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapMcpServerDefRepository}）。
 *
 * <p>Port 签名零改动；四条 SQL 逐字搬迁到 {@code CapMcpServerDefMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * {@code ON CONFLICT DO NOTHING}（不可变版本行）、枚举小写化、{@code exposed_tool_allowlist}
 * 的 null/[] 区分、非法枚举 fail-closed 抛错等行为全部与原实现一致。
 *
 * <p>platform 级对象（{@code cap_mcp_server_def} <b>不启用 RLS</b>），读取不受租户边界限制；
 * 但 mapper 走租户通道 SqlSessionFactory，故仍须绑定租户上下文（平台零 UUID 哨兵）——
 * 同一事务内若还访问了带 RLS 的表，缺少上下文会被 RLS 直接拒绝。
 * <p>⚠️ <b>不接租户级影子（P1 平台级豁免）</b>：本表<b>没有 {@code tenant_id} 列</b>、
 * 也没有 RLS 策略（{@code policy-mapping.yaml} 中无对应条目）⇒ 行<b>没有租户归属</b>，
 * 「染色」在语义上无从谈起；连接也走平台上下文而非租户上下文。
 * <p>因此本仓<b>刻意不持有 {@code ScopedDataAccess}</b>、<b>不调用影子钩子</b>：
 * 持有却不观测正是「装饰性接线」，会让 P1 的 {@code AUTHZ_MISMATCH=0} 变成假绿
 * （2026-11-09 实测：3 个 CAP 平台目录仓都曾如此，现已拆除）。
 * 该表的访问控制走平台级运维通道（{@code platform.resource.read} 等平台权限）。
 *
 * <p>⚠️ <b>{@code exposed_tool_allowlist} 的 null 与 [] 必须区分</b>（§4.4）：
 * {@code null} = 未配置（{@code ServerAllowedOps := ManifestOps}），
 * {@code []} = 显式空清单（{@code ServerAllowedOps := ∅}）。
 * 该列可空且无默认值，用可空 String 读取即可天然区分 SQL NULL（→ {@code null}）
 * 与 JSON 空数组（→ {@code "[]"}）。
 *
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapMcpServerDefRepository implements CapMcpServerDefPort {

    /** 平台级哨兵：零 UUID（见 {@link #platformContext()}）。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CapMcpServerDefMapper mapper;
    private final DataSource dataSource;

    public MybatisCapMcpServerDefRepository(@NonNull CapMcpServerDefMapper mapper,
                                            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapMcpServerDef def) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.insertVersion(
                        def.id(),
                        def.version(),
                        // ⚠️ DB 的 transport / auth_kind / trust_tier 取值域为小写
                        def.transport().name().toLowerCase(java.util.Locale.ROOT),
                        def.endpoint(),
                        def.command(),
                        def.authKind().name().toLowerCase(java.util.Locale.ROOT),
                        def.trustTier().name().toLowerCase(java.util.Locale.ROOT),
                        def.exposedToolAllowlist() == null
                                // ⚠️ null ≠ []（§4.4）：未配置与显式空清单语义不同，不可混淆
                                ? null
                                : writeJson(def.exposedToolAllowlist()),
                        def.connectionRisk().name(),
                        def.dynamicDiscovery(),
                        def.configFingerprint(),
                        def.credentialRef(),
                        def.schemaVersion(),
                        def.createdAt(),
                        // created_at / updated_at 同取 def.createdAt()，与旧 JDBC 的两次 setTimestamp 一致
                        def.createdAt(),
                        def.publishedAt()) > 0));
    }

    @Override
    public @NonNull Optional<CapMcpServerDef> find(@NonNull String serverId, int version) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.find(serverId, version)
                        .map(MybatisCapMcpServerDefRepository::toRecord)));
    }

    @Override
    public @NonNull Optional<CapMcpServerDef> findLatestPublished(@NonNull String serverId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.findLatestPublished(serverId)
                        .map(MybatisCapMcpServerDefRepository::toRecord)));
    }

    @Override
    public @NonNull Optional<CapMcpServerDef> findLatest(@NonNull String serverId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.findLatest(serverId)
                        .map(MybatisCapMcpServerDefRepository::toRecord)));
    }

    @Override
    public @NonNull List<CapMcpServerDef> listAll() {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.listAll().stream()
                                .map(MybatisCapMcpServerDefRepository::toRecord)
                                .toList()));
    }

    // ── DO → record（逐字段，与旧 JDBC 的 map() 对齐）──

    @Override
    public @NonNull List<CapMcpServerDef> listVersions(@NonNull String serverId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.listVersions(serverId).stream()
                                .map(MybatisCapMcpServerDefRepository::toRecord)
                                .toList()));
    }

    private static CapMcpServerDef toRecord(CapMcpServerDefDO d) {
        return new CapMcpServerDef(
                d.getId(),
                d.getVersion(),
                require(CapMcpServerDef.Transport.parse(d.getTransport()), "transport"),
                d.getEndpoint(),
                d.getCommand(),
                require(CapMcpServerDef.AuthKind.parse(d.getAuthKind()), "auth_kind"),
                require(McpTrustTier.parse(d.getTrustTier()), "trust_tier"),
                readAllowlist(d.getExposedToolAllowlist()),
                CapabilityRiskLevel.parse(d.getConnectionRisk()),
                d.isDynamicDiscovery(),
                d.getConfigFingerprint(),
                d.getCredentialRef(),
                d.getSchemaVersion(),
                d.getCreatedAt(),
                d.getPublishedAt());
    }

    /**
     * ⚠️ <b>枚举解析失败即抛错</b>（fail-closed）：DB 有 CHECK 约束兜底，
     * 真出现非法值说明数据被绕过约束写入了 —— 此时<b>不得</b>兜底成某个默认值
     * （例如把非法 transport 当成 http），必须让上层拒绝。
     */
    private static <T> @NonNull T require(@Nullable T parsed, @NonNull String column) {
        if (parsed == null) {
            throw new IllegalStateException("MCP Server 定义含非法 " + column + " 取值");
        }
        return parsed;
    }

    /**
     * @param json JSON 数组文本；<b>SQL NULL 时为 {@code null}</b>
     * @return 白名单；{@code null} 表示未配置，与显式空列表语义不同（§4.4）
     */
    @Nullable
    private static List<String> readAllowlist(@Nullable String json) {
        if (json == null) {
            // ⚠️ SQL NULL：未配置 —— 不可与 "[]" 混为一谈
            return null;
        }
        if (json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("解析 exposed_tool_allowlist 失败: " + json, e);
        }
    }

    /**
     * platform 级读取用的租户上下文（零 UUID 哨兵）。
     */
    private static TenantContext platformContext() {
        return new TenantContext(PLATFORM_SCOPE_TENANT, "system", Set.of(), 0L);
    }

    /**
     * 序列化为 JSON 文本。
     *
     * <p>⚠️ 序列化失败<b>抛异常</b>而非写空值：写空数组会让
     * 「显式不允许任何操作」被读成「未配置」——
     * 后者按 ServerAllowedOps 全集放行，是直接的权限放宽。
     */
    private static @NonNull String writeJson(@NonNull List<String> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 exposed_tool_allowlist 失败", e);
        }
    }

    /** 在租户作用域内执行：绑定 → 执行 → 无条件清理。 */
    private <T> T inTenantScope(java.util.function.Supplier<T> action) {
        TenantScope.bind(PLATFORM_SCOPE_TENANT); // 平台级哨兵，非客户端输入
        try {
            return action.get();
        } finally {
            TenantScope.clear(); // 无条件清理 Scope 与拦截器绑定标记
        }
    }
}
