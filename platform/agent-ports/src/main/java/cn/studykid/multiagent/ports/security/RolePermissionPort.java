package cn.studykid.multiagent.ports.security;

import java.util.Set;

import org.jspecify.annotations.NonNull;

/**
 * 角色→动作权限端口（<b>只含角色维度，不含版本维度</b>）。
 *
 * <p>⚠️ <b>为什么不能直接用 {@link AuthorizationService#isAuthorized} 做 Phase A 判定</b>：
 * 该接口把「版本陈旧」与「角色不足」压成同一个 {@code false}，
 * 而 §5.7 为二者规定了<b>不同</b>的原因码（{@code AUTHZ_STALE} / {@code AUTHZ_ROLE_MISSING}）。
 * 混用会导致「主体已被撤权」被报成「角色不足」—— 运维据此排查会指向错误的配置。
 *
 * <p>更严重的是<b>版本来源</b>问题：既有实现（{@code RoleBasedAuthorizationService}）
 * 的版本判定读的是<b>进程内内存 Map</b>，缺省值为 0L；
 * 而 §5.5 要求读<b>权威源</b>（DB 强一致）。
 * 若 Phase A 依赖该接口的内部版本判定，
 * 「DB 里已撤权（版本=5）但内存 Map 未同步」的实例上，
 * 上下文版本 1 < 内存当前 0 不成立 → <b>撤权被静默忽略</b>。
 *
 * <p>故 Phase A 的版本判定由 {@code InvocationValidator} 直接读
 * {@code AuthzVersionPort}（DB 强一致）完成，本端口<b>只回答角色问题</b>。
 */
public interface RolePermissionPort {

    /**
     * 判断给定角色集中是否有任一角色允许该动作。
     *
     * @param roles  主体持有的角色集合
     * @param action 动作标识（如 {@code tool:invoke}）
     * @return 允许返回 {@code true}；<b>未登记的动作与未登记的角色一律拒绝</b>
     */
    boolean permits(@NonNull Set<String> roles, @NonNull String action);
}
