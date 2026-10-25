package cn.studykid.multiagent.ports.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.orchestration.CapAgentChildRef;

/**
 * 父子 Agent 关系投影仓储端口（多级 Agent v1.1 · 06 变更补充「关系数据」）。
 *
 * <p>⚠️ <b>本端口存的是「发布关系投影」，不是权威定义</b>：
 * 权威来源是不可变 Agent release 中的 {@code childAgentRefs}。
 * 本表/本端口存在的意义是让「谁依赖谁」可以被<b>查询</b>与<b>分析</b>，
 * 而不是成为第二份真相 —— 因此本表允许重建，release 永不改写。
 *
 * <p>⚠️ 租户边界：必须经租户连接上下文访问（与 {@code cap_agent_def} 同口径）。
 */
public interface CapAgentChildRefPort {

    /**
     * 插入一条父子关系（<b>幂等</b>）。
     *
     * @param ref 关系投影
     * @return {@code true} 表示本次插入；{@code false} 表示已存在
     */
    boolean insert(@NonNull CapAgentChildRef ref);

    /**
     * 列出某个父 release 声明的全部子引用（按 {@code ordinal} 升序）。
     *
     * @param tenantId            租户标识
     * @param parentAgentId       父 Agent 标识
     * @param parentReleaseVersion 父 release 版本号
     * @return 子引用列表；无声明时为空列表
     */
    @NonNull List<CapAgentChildRef> listByParentRelease(@NonNull String tenantId,
                                                         @NonNull String parentAgentId,
                                                         int parentReleaseVersion);

    /**
     * 列出某个父 Agent 的<b>全部</b>关系边（<b>不限父 release 版本</b>）。
     *
     * <p>⚠️ <b>为什么不限版本</b>：委派拓扑图与「这个 Agent 声明了哪些子」
     * 问的是 <b>Agent</b> 而不是它的某个版本 —— 一个 Agent 历史上发过多个版本，
     * 每个版本都可能声明了不同的子集合，图上要呈现的是**全部**边，
     * 否则图会随版本选择而「缺边」，看起来像那个子没被依赖。
     * ⚠️ 结果里带 {@code parentReleaseVersion}，需要精确到版本的判断由调用方过滤。
     *
     * @param tenantId      租户标识
     * @param parentAgentId 父 Agent 标识
     * @return 该父 Agent 的全部关系边；无声明时为空列表
     */
    @NonNull List<CapAgentChildRef> listByParentAgent(@NonNull String tenantId,
                                                       @NonNull String parentAgentId);

    /**
     * <b>反查</b>：列出「声明了该子 Agent」的<b>全部父 release</b>（<b>不限子版本</b>）。
     *
     * <p>⚠️ <b>为什么需要反查</b>：影响分析与删除守卫问的是
     * 「<b>谁</b>依赖我」，而不是「我依赖谁」——
     * 出向查询回答不了「这个子 Agent 能不能被停用/删除」。
     * 没有反查，运维只能靠全表扫边来回答这个问题，
     * 而漏扫一次就等于放行了一次「被引用对象被抽走」的事故。
     *
     * <p>⚠️ <b>为什么不按子版本过滤</b>：被停用/删除的是<b>那个 Agent</b>，
     * 而不是一个版本 —— 任何父 release 引用了它的**任何一版**，
     * 都构成「抽走它会让那个父悬空」的事实。
     * ⚠️ 结果里带 {@code childReleaseVersion}，需要精确到版本的判断
     * （如「只有引用了 v2 的父受影响」）由调用方过滤。
     *
     * <p>⚠️ <b>跨父 release 的语义</b>：本方法返回<b>所有</b>声明了该子 Agent 的父，
     * 不区分父是否已发布 —— 判断「能不能停用」是调用方的责任
     * （它还要叠加「父是否已发布」「父的声明是否 optional」等条件）。
     *
     * @param tenantId      租户标识
     * @param childAgentId  子 Agent 标识
     * @return 声明了该子 Agent 的父引用列表；无人声明时为空列表
     */
    @NonNull List<CapAgentChildRef> listByChildAgent(@NonNull String tenantId,
                                                      @NonNull String childAgentId);

    /**
     * 列出某个租户的<b>全部</b>关系边（拓扑视图用）。
     *
     * <p>⚠️ <b>为什么要有全量查询</b>：委派拓扑图要渲染的是**整张图**，
     * 若只提供「按父查子」，前端就得对每个 Agent 发一次请求（N+1），
     * 既慢又容易在并发下拼出不一致的快照。
     * 一次取全量 → 一次渲染 → 图是自洽的。
     *
     * <p>⚠️ <b>租户边界</b>：本方法只返回本租户的边（RLS 强制 + 显式 WHERE）。
     *
     * @param tenantId 租户标识
     * @return 本租户的全部关系边；无数据时为空列表
     */
    @NonNull List<CapAgentChildRef> listByTenant(@NonNull String tenantId);
}
