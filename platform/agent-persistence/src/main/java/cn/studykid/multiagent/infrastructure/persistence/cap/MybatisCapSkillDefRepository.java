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

import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapabilityRiskLevel;
import cn.studykid.multiagent.core.cap.SkillDescription;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.infrastructure.persistence.cap.mapper.CapSkillDefMapper;
import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapSkillDefDO;
import cn.studykid.multiagent.infrastructure.persistence.rls.TenantScope;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;

/**
 * {@link CapSkillDefPort} 的 MyBatis-Plus 实现（对应 {@code JdbcCapSkillDefRepository}）。
 *
 * <p>Port 签名零改动；四条 SQL 逐字搬迁到 {@code CapSkillDefMapper.xml}，仅 {@code ?} → {@code #{param}}。
 * {@code ON CONFLICT DO NOTHING}（不可变版本行）、三级描述的 null/[] 语义、
 * JSON 解析失败一律 fail-closed 抛错等行为全部与原实现一致。
 *
 * <p>platform 级对象（{@code cap_skill_def} <b>不启用 RLS</b>），读取不受租户边界限制；
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
 * <p>⚠️ <b>{@code declaredMcp[].toolAllowlist} 的 null 与 [] 必须区分</b>（§4.4）：
 * {@code null} = 未配置（取 ServerAllowedOps 全集），{@code []} = 显式不允许任何操作。
 * 故 DTO 用 {@code @Nullable List<String>} 承载 —— Jackson 会把「键缺失」与「显式 null」
 * 都映射成 {@code null}，把 {@code []} 映射成空列表，语义得以保留。
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
public class MybatisCapSkillDefRepository implements CapSkillDefPort {

    /** 平台级哨兵：见 {@link #platformContext()} 的零 UUID 说明。 */
    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CapSkillDefMapper mapper;
    private final DataSource dataSource;

    public MybatisCapSkillDefRepository(@NonNull CapSkillDefMapper mapper,
                                        @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(@NonNull CapSkillDef def) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> {
                    // 旧单级 description 列：两级语义只走 purpose / usage，遗留列新入库一律 NULL
                    String purpose = null;
                    String usageJson = null;
                    String content = null;
                    if (def.description() != null) {
                        purpose = def.description().purpose();
                        usageJson = toJson(def.description().usage());
                        content = def.description().content();
                    }
                    // ⚠️ 位次（三级描述改造）：content 插在第 7 位，其后 declared_tools 及以后全部 +1。
                    //    ⚠️ 漏掉 +1 会把 content 覆盖掉、并让后续列整体错位
                    //       （schema_version 写进 published_at…）——
                    //       这类错误不会报错，只会静默写坏数据。
                    return mapper.insertVersion(
                            def.id(),
                            def.version(),
                            def.name(),
                            null, // description：遗留单级描述列，新入库一律 NULL
                            purpose,
                            usageJson,
                            content,
                            toJson(def.declaredTools()),
                            toJsonMcpDeclarations(def.declaredMcp()),
                            def.riskCeiling().name(),
                            def.paramPolicies(),
                            toJson(def.requiredCaps()),
                            def.configFingerprint(),
                            def.schemaVersion(),
                            def.createdAt(),
                            // created_at / updated_at 同取 def.createdAt()，与旧 JDBC 的两次 setTimestamp 一致
                            def.createdAt(),
                            def.publishedAt()) > 0;
                }));
    }

    @Override
    public @NonNull Optional<CapSkillDef> find(@NonNull String skillId, int version) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.find(skillId, version)
                        .map(MybatisCapSkillDefRepository::toRecord)));
    }

    @Override
    public @NonNull Optional<CapSkillDef> findLatestPublished(@NonNull String skillId) {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn -> mapper.findLatestPublished(skillId)
                        .map(MybatisCapSkillDefRepository::toRecord)));
    }

    @Override
    public @NonNull List<CapSkillDef> listAll() {
        return inTenantScope(() -> TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(), conn ->
                        mapper.listAll().stream()
                                .map(MybatisCapSkillDefRepository::toRecord)
                                .toList()));
    }

    // ── DO → record（逐字段，与旧 JDBC 的 map() 对齐）──

    private static CapSkillDef toRecord(CapSkillDefDO d) {
        return new CapSkillDef(
                d.getId(),
                d.getVersion(),
                d.getName(),
                readDescription(d.getPurpose(), d.getUsage(), d.getContent()),
                readStringList(d.getDeclaredTools()),
                readMcpDeclarations(d.getDeclaredMcp()),
                CapabilityRiskLevel.parse(d.getRiskCeiling()),
                d.getParamPolicies(),
                readStringList(d.getRequiredCaps()),
                d.getConfigFingerprint(),
                d.getSchemaVersion(),
                d.getCreatedAt(),
                d.getPublishedAt());
    }

    /**
     * 组装三级描述。
     *
     * <p>⚠️ 存量行只有遗留单级 {@code description}、三级列为 NULL，
     * 此时描述视为缺省（<b>不得</b>把遗留 description 充当 purpose ——
     * 选型信号与执行指引是新的行为内容，混装旧文本会误导模型）。
     */
    private static @Nullable SkillDescription readDescription(
            @Nullable String purpose, @Nullable String usageJson, @Nullable String content) {
        if (purpose == null || purpose.isBlank()) {
            return null;
        }
        List<SkillDescription.SkillUsage> usage;
        if (usageJson == null || usageJson.isBlank()) {
            usage = List.of();
        } else {
            try {
                usage = MAPPER.readValue(usageJson,
                        new TypeReference<List<SkillUsageDto>>() {
                        }).stream()
                        .map(dto -> new SkillDescription.SkillUsage(dto.when(), dto.how()))
                        .toList();
            } catch (JacksonException e) {
                // ⚠️ 读失败要显炸：把「指引存在」读成「没有指引」是行为侧的静默放宽
                throw new IllegalStateException("解析 usage 失败: " + usageJson, e);
            }
        }
        String contentText = (content == null || content.isBlank()) ? "" : content;
        return new SkillDescription(purpose, usage, contentText);
    }

    private static @NonNull List<String> readStringList(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JacksonException e) {
            // ⚠️ 与 P0 既有实现一致：存量脏数据不能静默当空列表
            //    （那会把「声明了 3 个 Tool」读成「一个都没声明」，授权被无声放宽）
            throw new IllegalStateException("解析 JSON 数组失败: " + json, e);
        }
    }

    private static @NonNull List<CapSkillDef.McpDeclaration> readMcpDeclarations(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<McpDeclarationDto> dtos =
                    MAPPER.readValue(json, new TypeReference<List<McpDeclarationDto>>() {
                    });
            return dtos.stream()
                    .map(dto -> new CapSkillDef.McpDeclaration(dto.serverId(), dto.toolAllowlist()))
                    .toList();
        } catch (JacksonException e) {
            throw new IllegalStateException("解析 declaredMcp 失败: " + json, e);
        }
    }

    /**
     * {@code declaredMcp[]} 的传输形态。
     *
     * @param serverId      MCP Server 标识
     * @param toolAllowlist 操作白名单；<b>{@code null} ≠ {@code []}</b>（§4.4）
     */
    private record McpDeclarationDto(@NonNull String serverId, @Nullable List<String> toolAllowlist) {
    }

    /**
     * {@code usage[]}（2级操作指引）的传输形态。
     */
    private record SkillUsageDto(@NonNull String when, @NonNull String how) {
    }

    /**
     * 序列化为 JSON 文本。
     *
     * <p>⚠️ 序列化失败<b>抛异常</b>而非写空值：写空数组会让
     * 「声明了 3 个 MCP server」被读成「一个都没声明」，授权被无声放宽。
     */
    private static @NonNull String toJson(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 JSON 失败", e);
        }
    }

    /**
     * 序列化 {@code declaredMcp}。
     *
     * <p>⚠️ <b>{@code null} 与 {@code []} 必须区分</b>（§4.4）：
     * {@code null} = 未配置（取 ServerAllowedOps 全集），
     * {@code []} = 显式不允许任何操作。
     * 故 DTO 用可空的 {@code toolAllowlist}，Jackson 会把「键缺失」与「显式 null」
     * 都写成 {@code null}，把 {@code []} 写成空数组，语义得以保留。
     */
    private static @NonNull String toJsonMcpDeclarations(
            @NonNull List<CapSkillDef.McpDeclaration> declarations) {
        return toJson(declarations.stream()
                .map(d -> new McpDeclarationDto(d.serverId(), d.toolAllowlist()))
                .toList());
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
     * platform 级读取用的租户上下文（零 UUID 哨兵，见
     * {@link JdbcCapToolDefRepository} 的同名说明）。
     */
    private static TenantContext platformContext() {
        return new TenantContext(PLATFORM_SCOPE_TENANT, "system", Set.of(), 0L);
    }
}
