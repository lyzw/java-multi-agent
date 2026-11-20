package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 资产发布流程策略配置（需求文档 §二.1）。
 *
 * <p>三层覆盖、按优先级生效：
 * <pre>
 *   INSTANCE（单实例覆盖） > RESOURCE_TYPE（按资源类型） > GLOBAL（全局默认）
 * </pre>
 *
 * <p>⚠️ <b>策略变更期间的锁粒度</b>：当目标资源处于审批流转或发布执行中时，
 * 应用层应拒绝该资源相关的策略更新（防止状态冲突）—— 此 record 本身
 * 不承载锁逻辑，锁由仓储层 / 应用层守护。
 *
 * @param policyId     策略标识
 * @param scope        作用域（PLATFORM / TENANT）
 * @param tenantId     租户标识；PLATFORM 作用域为 {@code null}
 * @param policyLevel  策略层级（GLOBAL / RESOURCE_TYPE / INSTANCE）
 * @param resourceKind 资源类型（AGENT / SKILL / TOOL / MCP_SERVER / RESULT_CONTRACT / ROUTING_POLICY）
 * @param resourceId   单实例覆盖时的资源 ID
 * @param publishMode  发布模式（DIRECT / MANUAL / APPROVAL_MANUAL / APPROVAL_AUTO）
 * @param enabled      是否启用（关闭后查优先级时跳过该策略）
 * @param updatedBy    最近修改人
 * @param createdAt    创建时间
 * @param updatedAt    最近修改时间
 */
public record PublishPolicy(
        @NonNull UUID policyId,
        @NonNull String scope,
        @Nullable String tenantId,
        @NonNull String policyLevel,
        @Nullable String resourceKind,
        @Nullable String resourceId,
        @NonNull String publishMode,
        boolean enabled,
        @NonNull String updatedBy,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt
) {

    /** 发布模式。 */
    public enum PublishMode {
        /** 免审直发：保存并校验通过后直接生效、推进线上发布指针。 */
        DIRECT,
        /** 手动发布：保存后进入待发布，需具备发布权限的人显式点击发布。 */
        MANUAL,
        /** 审批后手动发布：保存 → 待审核 → 审批通过 → 待发布 → 手动发布。 */
        APPROVAL_MANUAL,
        /** 审批后自动发布：保存 → 待审核 → 审批通过 → 自动执行发布。 */
        APPROVAL_AUTO;

        /** 是否包含审批环节。 */
        public boolean requiresApproval() {
            return this == APPROVAL_MANUAL || this == APPROVAL_AUTO;
        }

        /** 审批通过后是否自动执行发布。 */
        public boolean autoPublishOnApproval() {
            return this == DIRECT || this == APPROVAL_AUTO;
        }
    }

    /** 策略层级。 */
    public enum PolicyLevel {
        GLOBAL, RESOURCE_TYPE, INSTANCE;

        /** 数值优先级（越大越优先）。 */
        public int priority() {
            return switch (this) {
                case GLOBAL -> 1;
                case RESOURCE_TYPE -> 2;
                case INSTANCE -> 3;
            };
        }
    }

    public PublishPolicy {
        if (policyId == null) throw new IllegalArgumentException("policyId 不能为空");
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope 不能为空");
        if (policyLevel == null || policyLevel.isBlank()) throw new IllegalArgumentException("policyLevel 不能为空");
        if (publishMode == null || publishMode.isBlank()) throw new IllegalArgumentException("publishMode 不能为空");
        if (updatedBy == null || updatedBy.isBlank()) throw new IllegalArgumentException("updatedBy 不能为空");
        if (createdAt == null) createdAt = Instant.EPOCH;
        if (updatedAt == null) updatedAt = Instant.EPOCH;
        // scope 与 tenantId 一致性校验（与 DB CHECK 同款）
        if ("PLATFORM".equals(scope) && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM scope 不得携带 tenantId");
        }
        if ("TENANT".equals(scope) && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT scope 必须携带 tenantId");
        }
        // policyLevel 与资源字段一致性校验
        if ("GLOBAL".equals(policyLevel) && (resourceKind != null || resourceId != null)) {
            throw new IllegalArgumentException("GLOBAL 级别不得携带 resourceKind / resourceId");
        }
        if ("RESOURCE_TYPE".equals(policyLevel) && (resourceKind == null || resourceId != null)) {
            throw new IllegalArgumentException("RESOURCE_TYPE 级别必须携带 resourceKind 且不得携带 resourceId");
        }
        if ("INSTANCE".equals(policyLevel) && (resourceKind == null || resourceId == null)) {
            throw new IllegalArgumentException("INSTANCE 级别必须同时携带 resourceKind 与 resourceId");
        }
    }

    /** 计算优先级匹配分值（应用层优先级比较用）。 */
    public int matchScore(@NonNull String scope,
                          @Nullable String tenantId,
                          @Nullable String resourceKind,
                          @Nullable String resourceId) {
        int score = 0;
        // scope 必须匹配
        if (!this.scope.equals(scope)) return 0;
        // tenant 匹配
        if (tenantId != null && tenantId.equals(this.tenantId)) score += 100;
        // policyLevel 决定权重
        score += switch (this.policyLevel) {
            case "INSTANCE"     -> 300;
            case "RESOURCE_TYPE" -> 200;
            case "GLOBAL"        -> 100;
            default               -> 0;
        };
        if (this.resourceKind != null && this.resourceKind.equals(resourceKind)) score += 50;
        if (this.resourceId   != null && this.resourceId.equals(resourceId))   score += 10;
        return score;
    }

    /** 构造新实例，替换 createdAt / updatedAt（持久层回填时间戳用）。 */
    public PublishPolicy withTimestamps(Instant created, Instant updated) {
        return new PublishPolicy(
                policyId(), scope(), tenantId(), policyLevel(), resourceKind(), resourceId(),
                publishMode(), enabled(), updatedBy(),
                created, updated);
    }

}
