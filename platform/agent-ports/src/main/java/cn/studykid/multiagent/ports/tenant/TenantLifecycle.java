package cn.studykid.multiagent.ports.tenant;

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * 租户生命周期状态机（重规划 §6.3）。
 *
 * <p>平台<b>不直接介入租户业务</b>，只定义租户能使用的平台边界。
 * 状态机定义了租户生命周期的合法迁移，任何管理操作（暂停/恢复/退役）
 * 都必须经 {@link #canTransition(String, String)} 校验，非法迁移一律拒绝。
 *
 * <pre>
 *   PENDING_SETUP ──► ACTIVE
 *   ACTIVE        ──► SUSPENDED
 *   SUSPENDED     ──► ACTIVE
 *   PENDING_SETUP ──► CANCELLED
 *   ACTIVE        ──► DECOMMISSIONING ──► DECOMMISSIONED
 * </pre>
 *
 * <p>此状态机与 {@code platform.tenant.lifecycle_state} 强一致；
 * {@code public.tenant.status} 仅做镜像同步，运行时再验证以本状态机为准。
 */
public final class TenantLifecycle {

    private TenantLifecycle() {
    }

    /** 待初始化（创建后、首位租户管理员就位前）。 */
    public static final String PENDING_SETUP = "PENDING_SETUP";
    /** 运行中。 */
    public static final String ACTIVE = "ACTIVE";
    /** 已暂停（拒绝新业务请求，按策略处置进行中任务）。 */
    public static final String SUSPENDED = "SUSPENDED";
    /** 已取消（从未完成初始化）。 */
    public static final String CANCELLED = "CANCELLED";
    /** 退役中（保留计费/审计/终态元数据）。 */
    public static final String DECOMMISSIONING = "DECOMMISSIONING";
    /** 已退役。 */
    public static final String DECOMMISSIONED = "DECOMMISSIONED";

    /** 全部合法状态。 */
    public static final Set<String> ALL = Set.of(
            PENDING_SETUP, ACTIVE, SUSPENDED, CANCELLED, DECOMMISSIONING, DECOMMISSIONED);

    /**
     * 合法迁移表：{@code to -> 允许的来源状态集合}。
     * 不含互逆（如 ACTIVE→PENDING_SETUP）与跨级跳变（如 PENDING_SETUP→DECOMMISSIONED）。
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            PENDING_SETUP, Set.of(),
            ACTIVE, Set.of(PENDING_SETUP, SUSPENDED),
            SUSPENDED, Set.of(ACTIVE),
            CANCELLED, Set.of(PENDING_SETUP),
            DECOMMISSIONING, Set.of(ACTIVE),
            DECOMMISSIONED, Set.of(DECOMMISSIONING)
    );

    /** 当前状态是否允许承接新业务请求。 */
    public static boolean isActive(@NonNull String state) {
        return ACTIVE.equals(state);
    }

    /** 当前状态是否已暂停（应拒绝新业务并处置进行中任务）。 */
    public static boolean isSuspended(@NonNull String state) {
        return SUSPENDED.equals(state);
    }

    /** 当前状态是否已退役（不可恢复业务）。 */
    public static boolean isDecommissioned(@NonNull String state) {
        return DECOMMISSIONED.equals(state) || DECOMMISSIONING.equals(state);
    }

    /**
     * 校验从 {@code from} 到 {@code to} 的迁移是否合法。
     *
     * @throws IllegalArgumentException 状态本身非法
     */
    public static boolean canTransition(@NonNull String from, @NonNull String to) {
        if (!ALL.contains(from) || !ALL.contains(to)) {
            throw new IllegalArgumentException("非法租户状态：from=" + from + ", to=" + to);
        }
        return ALLOWED_TRANSITIONS.getOrDefault(to, Set.of()).contains(from);
    }

    /** 返回某状态的可达后继状态（用于错误提示/前端引导）。 */
    @NonNull
    public static Set<String> nextStates(@NonNull String from) {
        if (!ALL.contains(from)) {
            throw new IllegalArgumentException("非法租户状态：" + from);
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String to : ALL) {
            if (!to.equals(from) && canTransition(from, to)) {
                out.add(to);
            }
        }
        return out;
    }
}
