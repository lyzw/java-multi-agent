package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 租户-Agent 配置关联端口。
 *
 * <p>Agent 是全局实体，不归属任何租户。租户能否使用一个 Agent 完全由本端口维护的
 * {@code tenant_agent_binding} 行决定：一个 Agent 可绑定多个租户，一个租户可绑定多个 Agent。
 *
 * <p>绑定是直接配置 CRUD，不走发布工作流；但每次写操作都经审计端口留痕。
 */
public interface TenantAgentBindingPort {

    @NonNull List<BindingItem> listByTenant(@NonNull String tenantId);

    @NonNull List<BindingItem> listByAgent(@NonNull String agentId);

    @NonNull Optional<BindingItem> find(@NonNull String tenantId, @NonNull String agentId);

    /** 建立绑定；已存在则按配置更新并重新置为 ACTIVE。 */
    @NonNull BindingItem bind(@NonNull String tenantId,
                              @NonNull String agentId,
                              @Nullable Integer pinnedVersion,
                              @Nullable String config,
                              @NonNull String actorId);

    /** 仅更新绑定配置；绑定不存在时抛 {@link BindingNotFoundException}。 */
    @NonNull BindingItem update(@NonNull String tenantId,
                                @NonNull String agentId,
                                @Nullable Integer pinnedVersion,
                                @Nullable String config,
                                @NonNull String actorId);

    /** 逻辑移除：置为 SUSPENDED，保留历史与审计痕迹。 */
    void unbind(@NonNull String tenantId, @NonNull String agentId, @NonNull String actorId);

    record BindingItem(
            @NonNull String tenantId,
            @NonNull String agentId,
            @NonNull String status,
            @Nullable Integer pinnedVersion,
            @NonNull String config,
            @NonNull Instant createdAt,
            @NonNull Instant updatedAt
    ) {
    }

    class BindingNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BindingNotFoundException(String tenantId, String agentId) {
            super("租户-Agent 绑定不存在: tenant=" + tenantId + ", agent=" + agentId);
        }
    }
}
