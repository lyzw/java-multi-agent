package cn.studykid.multiagent.core.monitoring;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 授权版本水位（管理面 P4 运行监控）。
 *
 * <p><b>本记录回答的是「授权变更链路的整体推进情况」</b>：
 * 有多少主体持有授权版本、其中有多少版本被<b>撤销递增</b>过（撤权动作）、
 * 最新的版本推进到哪一步。
 *
 * <p>⚠️ <b>为什么「撤权计数」是本记录的核心指标</b>：
 * 撤权（角色变化 / 主体禁用 / 授权关系删除）通过
 * {@code cap_authz_version.authz_version} 的<b>原子递增</b>实现，
 * 递增即「此前的授权上下文全部失效」。因此：
 * <ul>
 *   <li>被递增过的主体数 = 发生过撤权的主体数；</li>
 *   <li>递增次数 = 该主体的撤权次数（单调递增，不回退）。</li>
 * </ul>
 * 这让「谁被撤过权」成为可观测事实，而不是靠翻日志猜。
 *
 * <p>⚠️ <b>口径边界：版本号 &gt; 1 不等于「当前被拒绝」</b>：
 * 版本 3 只说明「至少撤过两次」，不代表该主体现在没有权限。
 * 是否获授权须走 {@code PermissionResolver}（按发布指针算闭包）。
 * 本记录只陈述<b>版本推进事实</b>，不陈述授权结论。
 *
 * @param tenantId         租户标识
 * @param subjectCount     持有授权版本记录的主体数（含从未被撤权的主体）
 * @param revokedSubjects  被撤权过（{@code authz_version > 1}）的主体数
 * @param maxAuthzVersion  当前租户内最大授权版本号
 * @param lastBumpAt       最近一次版本递增时刻；无任何递增时为 {@code null}
 * @param observedAt       本次快照的观测时刻
 */
public record AuthzWatermark(
        @NonNull String tenantId,
        long subjectCount,
        long revokedSubjects,
        long maxAuthzVersion,
        @Nullable Instant lastBumpAt,
        @NonNull Instant observedAt) {

    public AuthzWatermark {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (subjectCount < 0) {
            throw new IllegalArgumentException("subjectCount 不得为负");
        }
        if (revokedSubjects < 0) {
            throw new IllegalArgumentException("revokedSubjects 不得为负");
        }
        if (revokedSubjects > subjectCount) {
            // ⚠️ 被撤权主体数不可能超过持有版本的主体数：
            //    出现即说明聚合口径写错了（如 JOIN 放大）。
            throw new IllegalArgumentException("revokedSubjects 不得超过 subjectCount");
        }
        if (maxAuthzVersion < 0) {
            throw new IllegalArgumentException("maxAuthzVersion 不得为负");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt 不能为空");
        }
    }

    /**
     * @return 是否发生过撤权（有主体版本被递增过）
     */
    public boolean hasRevocations() {
        return revokedSubjects > 0;
    }
}
