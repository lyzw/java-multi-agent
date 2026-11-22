package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.infrastructure.persistence.mapper.PlatformAgentDirectoryMapper;
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort;
import cn.studykid.multiagent.ports.persistence.cap.PlatformAgentDirectoryPort.PlatformAgentChange;

/**
 * PlatformAgentDirectoryPort MyBatis 实现（opsSqlSessionFactory + BYPASSRLS）。
 *
 * <p>⚠️ 租户绑定表带 FORCE RLS：绝不能像普通租户仓储那样用哨兵租户 + 主数据源查询，
 * 那会静默返回 0 行（页面显示「暂无 Agent」）。Mapper 已标 {@code @OpsMapper}，
 * 由运维工厂执行，这里直接调用即可。
 *
 * <p>⚠️ Mapper 绑定在 {@code opsSqlSessionFactory}：只有「MyBatis + 平台运维数据源」
 * 双开关同时打开时才生效，否则必须退回 Jdbc 实现（与 {@code MybatisModelCatalogRepository}
 * 同口径）。只开 MyBatis 开关时注册本 Bean 会因 Mapper 未扫描而让整个上下文启动失败。
 */
@Repository
@Primary
@ConditionalOnExpression("'${persistence.mybatis.enabled:false}'.equals('true') "
        + "&& '${agent.platform-ops.datasource.enabled:false}'.equals('true')")
public class MybatisPlatformAgentDirectoryRepository implements PlatformAgentDirectoryPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformAgentDirectoryMapper mapper;

    public MybatisPlatformAgentDirectoryRepository(@NonNull PlatformAgentDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public @NonNull List<PlatformAgentRow> listAgents(@Nullable String tenantIdFilter) {
        return mapper.listAgents(tenantIdFilter).stream()
                .map(r -> new PlatformAgentRow(
                        r.tenantId(), r.agentId(), r.name(), r.description(),
                        r.versionCount(), r.latestVersionNumber(), r.latestPublishedAt(),
                        r.defVersion(), r.defLifecycleState(),
                        listOf(r.defRoles()), listOf(r.defEnabledSkills()), listOf(r.defCapabilityTags()),
                        hasOverrides(r.defOverrides()), r.defModelRef(), listOf(r.defSkillBundles()),
                        hasNonEmptyJson(r.defInstructions()), hasNonEmptyJson(r.defOrchestration()),
                        r.orchestrationMode(), r.tenantCount(), r.pendingState(), r.pendingAt()))
                .toList();
    }

    @Override
    public @NonNull List<PlatformAgentChange> listAgentChanges(@NonNull String agentId, int limit) {
        return mapper.listAgentChanges(agentId, limit).stream()
                .map(r -> new PlatformAgentChange(
                        r.changeId(), r.category(), r.tenantId(), r.action(), r.state(),
                        r.targetVersion(), r.reason(), r.actorId(), r.requestedAt(), r.appliedAt()))
                .toList();
    }

    /** JSON 数组文本 → 列表；异常显式上抛（解析失败不静默清空）。 */
    private static List<String> listOf(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> items = JSON.readValue(json, new TypeReference<List<String>>() { });
            return items == null ? List.of() : items;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Agent 注册表 JSON 字段解析失败: " + json, e);
        }
    }

    private static boolean hasOverrides(@Nullable String overridesJson) {
        return overridesJson != null && !overridesJson.isBlank() && !overridesJson.trim().equals("{}");
    }

    private static boolean hasNonEmptyJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        String trimmed = json.trim();
        if (trimmed.equals("{}") || trimmed.equals("[]")) {
            return false;
        }
        try {
            Object parsed = JSON.readValue(trimmed, Object.class);
            if (parsed == null) {
                return false;
            }
            if (parsed instanceof java.util.Map<?, ?> m && m.isEmpty()) {
                return false;
            }
            if (parsed instanceof java.util.List<?> l && l.isEmpty()) {
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
