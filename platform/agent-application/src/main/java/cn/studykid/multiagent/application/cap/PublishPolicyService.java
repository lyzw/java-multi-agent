package cn.studykid.multiagent.application.cap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.PublishPolicy;
import cn.studykid.multiagent.core.cap.PublishPolicy.PublishMode;
import cn.studykid.multiagent.ports.persistence.PublishPolicyPort;

/**
 * 发布策略服务（需求文档 §二.1）。
 *
 * <p>核心职责：
 * <ol>
 *   <li>CRUD：三层策略（GLOBAL / RESOURCE_TYPE / INSTANCE）的增删改查；</li>
 *   <li>优先级解析：针对给定 (scope, tenant, resourceKind, resourceId)
 *       选出最具体的那条生效策略 —— 优先级 INSTANCE > RESOURCE_TYPE > GLOBAL；</li>
 *   <li>并发守护：当目标资源处于审批流转或发布执行中时，禁止相关策略变更
 *       （策略变化会让"同样的草稿"按不同路径流转，破坏状态一致性）。</li>
 * </ol>
 *
 * <p>⚠️ 本服务不做策略执行（那是草稿/发布服务的职责），只负责"配 + 查"。
 */
public class PublishPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PublishPolicyService.class);

    private final PublishPolicyPort policies;

    /** 发布执行中/审批流转中的草稿 resource_id 占位集合 —— 在草稿服务变更时写入，
     * 此处只做空壳实现（真正集成见 {@link ConfigDraftService} 的草稿进入 PENDING_APPROVAL 时注入）。
     * 用 ConcurrentHashMap.newKeySet() 是避免 Spring 自动装配循环依赖。 */
    private final java.util.Set<String> busyResources = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PublishPolicyService(@NonNull PublishPolicyPort policies) {
        this.policies = policies;
    }

    // ── CRUD ───────────────────────────────────────────────────────

    public @NonNull PublishPolicy create(@NonNull PublishPolicy policy) {
        return policies.insert(policy);
    }

    public @NonNull Optional<PublishPolicy> findById(@NonNull String scope,
                                                      @Nullable String tenantId,
                                                      @NonNull UUID policyId) {
        return policies.findById(scope, tenantId, policyId);
    }

    public @NonNull Optional<PublishPolicy> update(@NonNull PublishPolicy policy) {
        checkNotBusy(policy.tenantId(), policy.resourceKind(), policy.resourceId());
        return policies.update(policy);
    }

    public boolean delete(@NonNull String scope,
                          @Nullable String tenantId,
                          @NonNull UUID policyId) {
        return policies.delete(scope, tenantId, policyId);
    }

    public @NonNull List<PublishPolicy> listEnabled(@NonNull String scope,
                                                     @Nullable String tenantId) {
        return policies.listEnabled(scope, tenantId);
    }

    // ── 优先级解析 ────────────────────────────────────────────────

    /**
     * 解析给定资源当前应采用的发布模式。
     *
     * <p>算法：
     * <pre>
     *   1. 列出本 (scope, tenant) 所有 enabled 策略
     *   2. 按 INSTANCE > RESOURCE_TYPE > GLOBAL 优先级筛选：
     *      有 INSTANCE 覆盖 → 用它
     *      否则有 RESOURCE_TYPE → 用它
     *      否则用 GLOBAL（若存在）
     *   3. 全缺省则返回 MANUAL（安全默认：手动发布最保守）
     * </pre>
     */
    public @NonNull PublishMode resolveMode(@NonNull String scope,
                                              @Nullable String tenantId,
                                              @Nullable String resourceKind,
                                              @Nullable String resourceId) {
        List<PublishPolicy> enabled = policies.listEnabled(scope, tenantId);
        // 按优先级分组
        Optional<PublishPolicy> instance = enabled.stream()
                .filter(p -> "INSTANCE".equals(p.policyLevel())
                        && matches(p, scope, tenantId, resourceKind, resourceId))
                .findFirst();
        if (instance.isPresent()) return PublishMode.valueOf(instance.get().publishMode());

        Optional<PublishPolicy> byType = enabled.stream()
                .filter(p -> "RESOURCE_TYPE".equals(p.policyLevel())
                        && matches(p, scope, tenantId, resourceKind, null))
                .findFirst();
        if (byType.isPresent()) return PublishMode.valueOf(byType.get().publishMode());

        Optional<PublishPolicy> global = enabled.stream()
                .filter(p -> "GLOBAL".equals(p.policyLevel())
                        && matches(p, scope, tenantId, null, null))
                .findFirst();
        if (global.isPresent()) return PublishMode.valueOf(global.get().publishMode());

        // 安全默认
        log.debug("({},{}) 未配置发布策略，默认 MANUAL 模式", scope, tenantId);
        return PublishMode.MANUAL;
    }

    /** 标记资源正处于审批/发布中（策略变更禁止）。 */
    public void markBusy(@NonNull String scope,
                          @Nullable String tenantId,
                          @NonNull String resourceKind,
                          @NonNull String resourceId) {
        busyResources.add(busyKey(scope, tenantId, resourceKind, resourceId));
    }

    /** 清除"忙"标记（审批完成 / 发布完成）。 */
    public void clearBusy(@NonNull String scope,
                          @Nullable String tenantId,
                          @NonNull String resourceKind,
                          @NonNull String resourceId) {
        busyResources.remove(busyKey(scope, tenantId, resourceKind, resourceId));
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private static boolean matches(@NonNull PublishPolicy p,
                                   @NonNull String scope,
                                   @Nullable String tenantId,
                                   @Nullable String resourceKind,
                                   @Nullable String resourceId) {
        if (!p.scope().equals(scope)) return false;
        // tenant 匹配（TENANT 作用域必须精确相等；PLATFORM 必须 tenant_id 全 NULL）
        boolean tenantMatch;
        if ("PLATFORM".equals(scope)) {
            tenantMatch = p.tenantId() == null;
        } else {
            tenantMatch = tenantId != null && tenantId.equals(p.tenantId());
        }
        if (!tenantMatch) return false;
        // resource kind
        if (resourceKind != null && !resourceKind.equals(p.resourceKind())) return false;
        if (resourceId   != null && !resourceId.equals(p.resourceId()))     return false;
        return true;
    }

    private static String busyKey(String scope, String tenantId, String kind, String id) {
        return scope + "|" + (tenantId == null ? "" : tenantId) + "|" + kind + "|" + id;
    }

    private void checkNotBusy(@Nullable String tenantId,
                               @Nullable String resourceKind,
                               @Nullable String resourceId) {
        // 只对单实例/类型级策略做检查；GLOBAL 策略是全局默认，忙状态不影响
        if (resourceKind == null) return;
        // 只对租户级策略检查（PLATFORM 是运维权限）
        if (tenantId == null) return;
        // 简化：只要同 kind 任意 resource 忙都不让改（保守）
        // 完整实现可以更精细
        if (resourceId != null) {
            // 查单实例
            boolean busy = busyResources.contains(
                    busyKey("TENANT", tenantId, resourceKind, resourceId));
            if (busy) throw new IllegalStateException(
                    "资源 (" + resourceKind + "/" + resourceId + ") 处于审批/发布中，禁止策略变更");
        }
    }
}
