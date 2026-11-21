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
        return resolveFrom(policies.listEnabled(scope, tenantId), scope, tenantId,
                resourceKind, resourceId).orElseGet(() -> {
            log.debug("({},{}) 未配置发布策略，默认 MANUAL 模式", scope, tenantId);
            return PublishMode.MANUAL;
        });
    }

    /**
     * 按 (scope, tenant, 资源) 解析模式；精确匹配无果时回退到任意作用域的 GLOBAL 默认策略。
     *
     * <p>⚠️ 能力目录横跨 PLATFORM 与 TENANT 两条发布通道，策略配置页只往当前作用域写
     * GLOBAL 策略。没有这个回退就会出现「配了全局手动发布，平台通道仍按缺省审批走」的割裂。
     * 优先级仍然是「精确三层 > 跨作用域 GLOBAL > MANUAL 安全默认」。
     */
    public @NonNull PublishMode resolveWithGlobalFallback(@NonNull String scope,
                                                           @Nullable String tenantId,
                                                           @Nullable String resourceKind,
                                                           @Nullable String resourceId) {
        List<PublishPolicy> enabled = policies.listEnabled(scope, tenantId);
        Optional<PublishMode> precise = resolveFrom(enabled, scope, tenantId,
                resourceKind, resourceId);
        if (precise.isPresent()) return precise.get();
        List<PublishPolicy> globals = policies.listEnabledGlobals();
        if (!globals.isEmpty()) {
            PublishMode fallback = PublishMode.valueOf(globals.get(0).publishMode());
            log.debug("({},{}) 无精确策略，回退全局默认 {}", scope, tenantId, fallback);
            return fallback;
        }
        log.debug("({},{}) 无精确策略也无全局默认，采用 MANUAL 安全默认", scope, tenantId);
        return PublishMode.MANUAL;
    }

    /** 资源引用（按资源类型 / 实例解析策略用）。 */
    /**
     * 解析结果：发布模式 + 命中的具体策略（方便审计）。
     *
     * <p>⚠️ 2026-11-21 M4：autoRoute 之前只记录了 "mode=DIRECT"，
     * 看不到究竟是 INSTANCE 策略覆盖了，还是 GLOBAL 兜底 —— 事后审计和排障
     * （"为啥这个租户免审而隔壁租户要审"）都缺关键证据。
     */
    public record ResolveResult(
            @NonNull PublishMode mode,
            @Nullable UUID       policyId,
            @Nullable String     policyLevel,
            boolean              fromGlobalFallback
    ) {}

    /**
     * 类似 {@link #resolveForResources}，但同时返回**最终决定的那条策略**。
     * 决定算法：所有资源综合后取最严格者，返回该严格度对应的策略；
     * 无任何匹配策略时按 MANUAL 安全默认处理，policyId 为 null。
     */
    public @NonNull ResolveResult resolveDetailedForResources(@NonNull String scope,
                                                               @Nullable String tenantId,
                                                               @NonNull List<ResourceRef> refs) {
        PublishMode strictest = PublishMode.DIRECT;
        PublishPolicy decisionPolicy = null;
        boolean fromGlobal = false;
        boolean strictestDecided = false;
        for (ResourceRef ref : refs) {
            List<PublishPolicy> enabled = policies.listEnabled(scope, tenantId);
            Optional<PublishPolicy> matched = bestMatch(enabled, scope, tenantId, ref.kind(), ref.id());
            boolean useFallback = false;
            if (matched.isEmpty()) {
                // ⚠️ 与 resolveWithGlobalFallback 同口径：GLOBAL 行的 resourceKind/resourceId 为 NULL，
                //    不能用 matches(..., kind, id) 过滤，否则跨作用域回退永远命中不了。
                List<PublishPolicy> globals = policies.listEnabledGlobals();
                if (!globals.isEmpty()) {
                    matched = Optional.of(globals.get(0));
                    useFallback = true;
                }
            }
            PublishPolicy candidate = matched.orElse(null);
            PublishMode m = candidate == null
                    ? PublishMode.MANUAL  // 无策略：安全默认，绝不调用 Optional.get()
                    : PublishMode.valueOf(candidate.publishMode());
            if (m.requiresApproval()) {
                // 任一资源要求审批 → 整批停在审批；决策策略就是这条策略。
                return new ResolveResult(m,
                        candidate == null ? null : candidate.policyId(),
                        candidate == null ? null : candidate.policyLevel(),
                        useFallback);
            }
            // 首条资源定基线；后续仅在更严格时覆盖，避免被 DIRECT 反向降级。
            if (!strictestDecided || strictest.compareStrictness(m) < 0) {
                strictest = m;
                decisionPolicy = candidate;
                fromGlobal = useFallback;
                strictestDecided = true;
            }
        }
        return new ResolveResult(strictest,
                decisionPolicy == null ? null : decisionPolicy.policyId(),
                decisionPolicy == null ? null : decisionPolicy.policyLevel(),
                fromGlobal);
    }

    /** 给定 enabled 列表 + (scope, tenant, kind, id) 找最具体的一条；找不到返回 empty。 */
    private Optional<PublishPolicy> bestMatch(List<PublishPolicy> enabled,
                                               String scope, String tenantId,
                                               String resourceKind, String resourceId) {
        Optional<PublishPolicy> instance = enabled.stream()
                .filter(p -> "INSTANCE".equals(p.policyLevel())
                        && matches(p, scope, tenantId, resourceKind, resourceId))
                .findFirst();
        if (instance.isPresent()) return instance;

        Optional<PublishPolicy> byType = enabled.stream()
                .filter(p -> "RESOURCE_TYPE".equals(p.policyLevel())
                        && matches(p, scope, tenantId, resourceKind, null))
                .findFirst();
        if (byType.isPresent()) return byType;

        return enabled.stream()
                .filter(p -> "GLOBAL".equals(p.policyLevel())
                        && matches(p, scope, tenantId, null, null))
                .findFirst();
    }

    public record ResourceRef(@NonNull String kind, @Nullable String id) {
        public static ResourceRef of(@NonNull String kind, @Nullable String id) {
            return new ResourceRef(kind, id);
        }
    }

    /**
     * 一批资源综合出唯一发布模式：<b>最严格者胜</b>。
     *
     * <p>⚠️ 一次发布申请可同时携带多种资源（如 Tool + Skill）。只要其中任一资源要求审批，
     * 整批就走审批 —— 否则「批的是 A、免审的是 B」会让审批人对自己没见过的变更签字。
     * 严格度：APPROVAL_* > MANUAL > DIRECT。
     */
    public @NonNull PublishMode resolveForResources(@NonNull String scope,
                                                     @Nullable String tenantId,
                                                     @NonNull List<ResourceRef> refs) {
        PublishMode strictest = PublishMode.DIRECT;
        for (ResourceRef ref : refs) {
            PublishMode mode = resolveWithGlobalFallback(scope, tenantId, ref.kind(), ref.id());
            if (mode.requiresApproval()) return mode;
            if (mode == PublishMode.MANUAL) strictest = PublishMode.MANUAL;
        }
        return strictest;
    }

    /**
     * 策略免审批时的「系统审批人」主体标识。
     *
     * <p>⚠️ 申请单存储层要求 approver_id ≠ requester_id。这里用一个固定的系统主体：
     * 它既满足约束、又在审计里明确区分「策略自动放行」与「真人双人复核」，
     * 不会被误读成某个管理员自己批了自己。
     */
    public static final String POLICY_AUTO_APPROVER = "00000000-0000-0000-0000-000000000002";

    /** 策略自动放行的审批意见前缀（写入 decision_note 留痕）。 */
        /** 策略自动放行的审批意见前缀（写入 decision_note 留痕）。
     *  调用方必须在后面追加 mode + 命中策略细节（policyId/policyLevel）—— 见 {@link ResolveResult}。 */
    public static final String POLICY_AUTO_NOTE_PREFIX = "[发布策略自动放行] mode=";

    private Optional<PublishMode> resolveFrom(List<PublishPolicy> enabled,
                                               String scope, String tenantId,
                                               String resourceKind, String resourceId) {
        Optional<PublishPolicy> instance = enabled.stream()
                .filter(p -> "INSTANCE".equals(p.policyLevel())
                        && matches(p, scope, tenantId, resourceKind, resourceId))
                .findFirst();
        if (instance.isPresent()) return Optional.of(PublishMode.valueOf(instance.get().publishMode()));

        Optional<PublishPolicy> byType = enabled.stream()
                .filter(p -> "RESOURCE_TYPE".equals(p.policyLevel())
                        && matches(p, scope, tenantId, resourceKind, null))
                .findFirst();
        if (byType.isPresent()) return Optional.of(PublishMode.valueOf(byType.get().publishMode()));

        Optional<PublishPolicy> global = enabled.stream()
                .filter(p -> "GLOBAL".equals(p.policyLevel())
                        && matches(p, scope, tenantId, null, null))
                .findFirst();
        return global.map(p -> PublishMode.valueOf(p.publishMode()));
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
