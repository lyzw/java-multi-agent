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
}
