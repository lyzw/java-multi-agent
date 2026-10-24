package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapAgentDef;

/**
 * Agent 结构化定义仓储端口（§6.1 / 06 §1.4 · W-PERM-P0）。
 *
 * <p>⚠️ <b>写入语义是「插入不可变版本行」</b>，不是「更新」：
 * 改配置只能发新版本（{@code version+1}），已发布行<b>不可变</b>（§6.1）。
 * 数据库层有触发器兜底，本端口不应尝试 UPDATE 已发布行。
 */
public interface CapAgentDefPort {

    /**
     * 插入一条版本行。
     *
     * <p>幂等：同一 {@code (tenantId, agentId, version)} 已存在时返回 {@code false}，
     * <b>不覆盖</b>既有行（不可变版本行）。
     *
     * @return {@code true} 表示本次插入；{@code false} 表示已存在
     */
    boolean insert(@NonNull CapAgentDef def);

    /**
     * 按精确版本读取。
     */
    @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                         @NonNull String agentId,
                                         int version);

    /**
     * 读取最新版本行（按 version 倒序取第一行，<b>不</b>按发布状态过滤）。
     *
     * <p>⚠️ 「当前生效版本」<b>不</b>由本方法决定 —— 那是发布头指针的职责
     * （§6.3）。本方法只用于迁移期比对与诊断。
     */
    @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                               @NonNull String agentId);

    /**
     * 读取<b>最新已发布</b>版本行（按 version 倒序取第一行，<b>按发布状态过滤</b>）。
     *
     * <p>⚠️ 与 {@link #findLatest} 的区别：本方法只返回 {@code published_at IS NOT NULL} 的行，
     * 因此不会把<b>草稿</b>版本当成当前版本（草稿不可被调用，见 §6.1「不可变版本行」语义）。
     *
     * <p>⚠️ 同 {@code CapToolDefPort.findLatestPublished}：该口径不等价于
     * 「当前发布所指版本」（§6.3），仅用于授权期新鲜度校验与诊断。
     *
     * @param tenantId 租户标识
     * @param agentId  Agent 标识
     * @return 最新已发布版本行；无已发布版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                        @NonNull String agentId);

    /**
     * 列出该租户<b>每个 Agent 的最高版本行</b>（不按发布状态过滤）。
     *
     * <p>⚠️ <b>为什么需要整租户列举</b>：依赖图谱 / 影响分析要基于全租户 Agent 的
     * <b>结构化声明</b>（{@code enabled_skills} / {@code overrides}）计算边，
     * 而 {@code AgentQueryPort} 只透传自由 JSON {@code definition}，不满足。
     *
     * <p>⚠️ 与 {@code CapToolDefPort#listAll()} 同口径：取最高版本行（含未发布），
     * 由调用方按 {@link CapAgentDef#isPublished()} 标注；「当前生效版本」以发布
     * 指针为准（§6.3），本方法只服务于管理面图谱展示。
     *
     * <p>⚠️ <b>RLS 绑定</b>：{@code cap_agent_def} 是租户隔离表，必须经
     * {@code tenantConnection} 绑定后查询，绕过即绕过隔离。
     *
     * @param tenantId 租户标识
     * @return 每 Agent 一行的最高版本定义（按 agent_id 升序）；无 Agent 时为空列表
     */
    @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId);
}
