package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 配置发布头（§6.1 / 06 §1.7）。
 *
 * <p><b>当前生效版本的权威解析</b>（06 §3.2）：运行时当前配置 = 最近一条
 * {@code status=PUBLISHED} 的发布头的 {@code objectVersions}；
 * <b>绝不</b>以 {@code is_current} 之类可变标志判断，
 * <b>绝不</b>扫描历史行、<b>绝不</b>以 {@code max(configPublishSeq)} 选当前
 * （§6.3 第 2 条）。
 *
 * <p>⚠️ <b>作用域</b>（P0 落地决策，待 P5 复核）：V1 的 {@code cap_agent_def} 是
 * <b>租户级</b>，若发布头为 platform 级且不带 RLS，其 {@code objectVersions}
 * 会把各租户的 Agent 结构写进任何租户都能读到的表 —— 跨租户结构泄漏。
 * 故本模型用 {@link Scope} 显式区分 PLATFORM / TENANT 两种作用域。
 *
 * @param releaseId           发布记录标识
 * @param configPublishSeq    全局单调发布序号（<b>仅用于追溯，不用于选当前</b>）
 * @param scope               作用域
 * @param tenantId            租户标识；{@link Scope#PLATFORM} 时为 {@code null}
 * @param objectVersions      本次发布的对象版本列表（JSON 文本）
 * @param aggregateFingerprint 聚合配置指纹
 * @param status              发布状态
 * @param requesterId         申请人
 * @param approverId          审批人（双人复核：不得等于申请人）
 * @param effectiveAt         生效时间
 * @param createdAt           创建时间
 */
public record ConfigRelease(
        long releaseId,
        long configPublishSeq,
        @NonNull Scope scope,
        @Nullable String tenantId,
        @NonNull String objectVersions,
        @Nullable String aggregateFingerprint,
        @NonNull Status status,
        @Nullable String requesterId,
        @Nullable String approverId,
        @Nullable Instant effectiveAt,
        @NonNull Instant createdAt
) {

    /** 发布作用域。 */
    public enum Scope {
        /** 平台级：只含 platform 对象版本（cap_tool_def / cap_skill_def / cap_mcp_server_def）。 */
        PLATFORM,
        /** 租户级：只含本租户对象版本。 */
        TENANT
    }

    /** 发布状态（06 §1.7）。 */
    public enum Status {
        /** 未提交/准备中；运行期<b>绝不可读到</b>（06 §3.2 第 5 条）。 */
        PREPARING,
        /** 已发布；当前版本只认它。 */
        PUBLISHED,
        /** 已回滚；<b>仅作审计旁路</b>，不参与当前版本解析（§6.3 第 7 条）。 */
        ROLLED_BACK
    }

    public ConfigRelease {
        // ⚠️ 允许 0：0 表示「序号尚未分配」。
        //    序号由 **DB 序列**分配（§6.3 第 2 条），调用方在保存前无从得知，
        //    强行要求调用方先填一个值只会诱使它「先读后写」—— 那正是被明令禁止的。
        if (configPublishSeq < 0) {
            throw new IllegalArgumentException("configPublishSeq 不得为负数");
        }
        if (objectVersions == null || objectVersions.isBlank()) {
            throw new IllegalArgumentException("objectVersions 不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (scope == Scope.TENANT && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT 作用域必须携带 tenantId");
        }
        if (scope == Scope.PLATFORM && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM 作用域不得携带 tenantId");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }

    /**
     * 构造一条<b>待保存</b>的发布记录（序号尚未分配）。
     *
     * <p>⚠️ 序号留给 DB 序列分配：调用方先读后写会引入竞态，
     * 且「读到的序号」在并发下随时可能已被别人占用。
     *
     * @param scope                作用域
     * @param tenantId             租户标识；PLATFORM 作用域传 {@code null}
     * @param objectVersions       本次发布的对象版本列表（JSON 文本）
     * @param aggregateFingerprint 聚合指纹
     * @param status               发布状态
     * @param requesterId          申请人
     * @param approverId           审批人
     * @param effectiveAt          生效时间
     * @param createdAt            创建时间
     */
    public static @NonNull ConfigRelease pending(ConfigRelease.@NonNull Scope scope,
                                                  @Nullable String tenantId,
                                                  @NonNull String objectVersions,
                                                  @Nullable String aggregateFingerprint,
                                                  @NonNull Status status,
                                                  @Nullable String requesterId,
                                                  @Nullable String approverId,
                                                  @Nullable Instant effectiveAt,
                                                  @NonNull Instant createdAt) {
        return new ConfigRelease(0L, 0L, scope, tenantId, objectVersions,
                aggregateFingerprint, status, requesterId, approverId, effectiveAt, createdAt);
    }

    /**
     * 双人复核校验（06 §3 状态机 approve 行）。
     *
     * <p>⚠️ 同一 principal 同时具备申请与审批两个动作时，双人复核靠
     * <b>身份比对</b>兜底，而非靠权限拆分（应用层 + DB CHECK 双保险）。
     *
     * @return 校验通过返回 {@code true}
     */
    public boolean satisfiesTwoPersonReview() {
        return requesterId == null || approverId == null || !requesterId.equals(approverId);
    }
}
