package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 主体授权版本（DB 化 authzVersion）。
 *
 * <p><b>为什么必须是 DB 支撑而非内存</b>（W-PERM-P0 / 04 §1.1 前置修正）：
 * 本平台是<b>多租户 SaaS、多实例部署</b>，单实例内存版
 * {@code RoleBasedAuthorizationService} 无法满足「撤权即时全实例失效」——
 * 一个实例撤销了授权，另一个实例的内存 Map 仍持有旧值，
 * 攻击者可用<b>曾经合法</b>的身份在另一实例继续越权。
 *
 * <p><b>递增语义</b>（06 §1.5）：
 * <ul>
 *   <li>初始值 1（首次创建主体授权记录时写入）；</li>
 *   <li>单调递增，<b>由 DB 原子操作完成</b>，禁止应用先读后加一；</li>
 *   <li>角色变化 / 主体禁用 / 授权关系删除撤销 <b>均</b> bump；</li>
 *   <li>撤权与版本递增同事务提交；</li>
 *   <li>重复撤权只继续递增，<b>不产生版本回退</b>。</li>
 * </ul>
 *
 * @param tenantId      租户标识（版本作用域为 {@code (tenantId, principalId)}）
 * @param principalId   主体标识
 * @param authzVersion  单调递增的授权版本号
 * @param updatedAt     最后一次 bump 时间
 */
public record AuthzVersion(
        @NonNull String tenantId,
        @NonNull String principalId,
        long authzVersion,
        @NonNull Instant updatedAt
) {

    /** 首次创建时的初始版本号（06 §1.5 第 1 条）。 */
    public static final long INITIAL_VERSION = 1L;

    public AuthzVersion {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId 不能为空");
        }
        if (authzVersion < INITIAL_VERSION) {
            throw new IllegalArgumentException(
                    "authzVersion 不得小于初始值 " + INITIAL_VERSION + "，收到: " + authzVersion);
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为空");
        }
    }

    /**
     * 判断给定版本是否已过期（<b>陈旧</b>）。
     *
     * <p>语义：持有 {@code candidate} 的上下文，当 {@code candidate < current} 时
     * 说明其授权已被撤销 —— 必须拒绝。
     *
     * @param candidate 调用方携带的授权版本
     * @return {@code true} 表示已陈旧（应拒绝）
     */
    public boolean isStale(long candidate) {
        return candidate < authzVersion;
    }
}
