package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.CapToolKind;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapToolDefMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapToolDefDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;

/**
 * {@link CapToolDefPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapToolDefRepository}）。
 *
 * <p>Port 签名零改动；六条 SQL 逐字搬迁到 {@code CapToolDefMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * {@code ON CONFLICT DO NOTHING}（不可变版本行）、kind 小写化、HTTP 三件套的 NULL 语义、
 * fail-closed 的非法 kind/残缺 HTTP 配置校验全部与原实现一致。
 *
 * <p>platform 级对象（{@code cap_tool_def} <b>不启用 RLS</b>），读取不受租户边界限制；
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
 * <p>⚠️ <b>默认关闭</b>：仅当 {@code persistence.mybatis.enabled=true} 时注册为 Bean；
 * 关闭时旧 Jdbc 实现生效（旧类带互补条件，matchIfMissing=true）。
 *
 * <p><b>RLS 保证</b>：每个方法在 {@link TenantScope} 内执行（try/finally 无条件清理），
 * 并经由 {@link TransactionalConnectionHolder#inTransaction} 借用同一条事务连接，与原实现一致。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisCapToolDefRepository implements CapToolDefPort {

    /** 平台级读取用的租户上下文（零 UUID 哨兵，见 {@link #platformContext()}）。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private final CapToolDefMapper mapper;
    private final DataSource dataSource;

    public MybatisCapToolDefRepository(@NonNull CapToolDefMapper mapper,
                                       @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapToolDef def) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> {
                    CapHttpToolConfig http = def.httpConfig();
                    return mapper.insertVersion(
                            def.id(),
                            def.version(),
                            def.name(),
                            // ⚠️ DB 的 kind 取值域是小写（'native' / 'mcp' / 'http'）
                            def.kind().name().toLowerCase(java.util.Locale.ROOT),
                            def.riskLevel().name(),
                            def.mcpServerId(),
                            def.mcpToolName(),
                            // ⚠️ HTTP 三件套：非 HTTP 工具必须写 NULL，
                            //    绝不能写空串或 0 —— DB 的 CHECK 会拒绝「非 HTTP 却带 http 配置」，
                            //    而空串/0 能骗过「是否为空」的判断。
                            http == null ? null : http.method(),
                            http == null ? null : http.url(),
                            http == null ? null : http.timeoutMs(),
                            def.inputSchema(),
                            def.paramPolicies(),
                            def.description(),
                            def.configFingerprint(),
                            def.schemaVersion(),
                            def.createdAt(),
                            // created_at / updated_at 同取 def.createdAt()，与旧 JDBC 的两次 setTimestamp 一致
                            def.createdAt(),
                            def.publishedAt()) > 0;
                }));
    }

    @Override
    public @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.find(toolId, version)
                        .map(MybatisCapToolDefRepository::toRecord)));
    }

    @Override
    public @NonNull Optional<CapToolDef> findLatestPublished(@NonNull String toolId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.findLatestPublished(toolId)
                        .map(MybatisCapToolDefRepository::toRecord)));
    }

    @Override
    public boolean publishDraft(@NonNull String id, int version,
                                @NonNull Instant publishedAt) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.publishDraft(id, version, publishedAt) > 0));
    }

    public boolean updateDraft(@NonNull CapToolDef def) {
        CapHttpToolConfig http = def.httpConfig();
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.updateDraft(
                        def.id(),
                        def.version(),
                        def.name(),
                        def.kind().name().toLowerCase(java.util.Locale.ROOT),
                        def.riskLevel().name(),
                        def.mcpServerId(),
                        def.mcpToolName(),
                        http == null ? null : http.method(),
                        http == null ? null : http.url(),
                        http == null ? null : http.timeoutMs(),
                        def.inputSchema(),
                        def.paramPolicies(),
                        def.description(),
                        def.configFingerprint(),
                        java.time.Instant.now()) > 0));
    }

    @Override
    public @NonNull Optional<CapToolDef> findLatest(@NonNull String toolId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.findLatest(toolId)
                        .map(MybatisCapToolDefRepository::toRecord)));
    }

    @Override
    public @NonNull Optional<CapToolDef> findByMcpBinding(@NonNull String mcpServerId,
                                                           @NonNull String mcpToolName) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.findByMcpBinding(mcpServerId, mcpToolName)
                                .map(MybatisCapToolDefRepository::toRecord)));
    }

    @Override
    public @NonNull List<CapToolDef> listByMcpServer(@NonNull String mcpServerId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.listByMcpServer(mcpServerId).stream()
                                .map(MybatisCapToolDefRepository::toRecord)
                                .toList()));
    }

    @Override
    public @NonNull List<CapToolDef> listAll() {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.listAll().stream()
                                .map(MybatisCapToolDefRepository::toRecord)
                                .toList()));
    }

    // ── DO → record（逐字段，与旧 JDBC 的 map() 对齐）──

    @Override
    public @NonNull List<CapToolDef> listVersions(@NonNull String toolId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.listVersions(toolId).stream()
                                .map(MybatisCapToolDefRepository::toRecord)
                                .toList()));
    }

    private static CapToolDef toRecord(CapToolDefDO d) {
        return new CapToolDef(
                d.getId(),
                d.getVersion(),
                d.getName(),
                requireKind(d.getKind()),
                CapabilityRiskLevel.parse(d.getRiskLevel()),
                d.getMcpServerId(),
                d.getMcpToolName(),
                mapHttpConfig(d),
                d.getInputSchema(),
                d.getParamPolicies(),
                d.getDescription(),
                d.getConfigFingerprint(),
                d.getSchemaVersion(),
                d.getCreatedAt(),
                d.getPublishedAt());
    }

    /**
     * 读取 HTTP 配置列。
     *
     * <p>⚠️ <b>以 method 列为准</b>：三列要么齐全要么全空（DB 有 CHECK 兜底），
     * 所以读 {@code http_method} 是否为 NULL 就足以判定「这是不是 HTTP 工具」。
     * 若三列残缺（例如绕过约束写进来的脏数据），这里<b>抛异常</b>而不是补默认值 ——
     * 补默认值会造出一个「能跑但目标错了」的 Tool，那比读不出来更危险。
     */
    private static @Nullable CapHttpToolConfig mapHttpConfig(CapToolDefDO d) {
        String method = d.getHttpMethod();
        String url = d.getHttpUrl();
        // ⚠️ 用可空 Integer 判空而不是「getInt + wasNull」：
        //    wasNull() 只反映**最后一次**读，调用顺序一变就静默读错 ——
        //    这里宁可多一次装箱，也要让「这一列到底是不是 NULL」与读取动作绑在一起。
        Integer timeout = d.getHttpTimeoutMs();
        if (method == null) {
            if (url != null || timeout != null) {
                throw new IllegalStateException(
                        "Tool 定义含残缺的 HTTP 配置（缺方法）：URL/超时必须同时为空");
            }
            return null;
        }
        if (url == null || timeout == null) {
            throw new IllegalStateException(
                    "Tool 定义含残缺的 HTTP 配置：方法/URL/超时必须三者齐全");
        }
        return new CapHttpToolConfig(method, url, timeout);
    }

    /**
     * ⚠️ <b>非法 kind 一律抛错</b>（fail-closed）：<b>绝不兜底成 {@code NATIVE}</b> ——
     * 那会把「类型未知的 MCP 工具」当成原生工具放行，让本该走 MCP 绑定校验的调用绕过它。
     * DB 有 CHECK 约束兜底，真出现非法值说明数据被绕过了约束。
     */
    private static @NonNull CapToolKind requireKind(@Nullable String raw) {
        CapToolKind kind = CapToolKind.parse(raw);
        if (kind == null) {
            throw new IllegalStateException("Tool 定义含非法 kind 取值: " + raw);
        }
        return kind;
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

    /**
     * platform 级读取用的租户上下文。
     *
     * <p>⚠️ <b>零 UUID 哨兵</b>：{@code current_tenant_id()} 的实现是
     * {@code NULLIF(current_setting('app.tenant_id'),'')::UUID}，
     * 绑非 UUID 文本会直接类型转换报错。零 UUID 是合法 UUID 且不对应任何真实租户。
     */
    private static TenantContext platformContext() {
        return new TenantContext(PLATFORM_SCOPE_TENANT, "system", Set.of(), 0L);
    }
}
