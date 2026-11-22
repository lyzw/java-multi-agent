package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
    @NonNull Optional<CapAgentDef> find(@Nullable String tenantId,
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
     * 读取最新版本行，<b>不按租户绑定过滤</b>。
     *
     * <p>⚠️ 仅供 PLATFORM 作用域发布全局 Agent 时计算版本号使用 —— 全局 Agent 可以
     * 尚无任何 {@code tenant_agent_binding}，走 {@link #findLatest} 会因绑定 EXISTS
     * 过滤而永远返回空（版本号恒从 1 起、重复发布撞号）。默认实现委托哨兵租户，
     * JDBC 实现覆写为无绑定过滤的查询。
     */
    default @NonNull Optional<CapAgentDef> findLatestGlobal(@NonNull String agentId) {
        return findLatest("00000000-0000-0000-0000-000000000000", agentId);
    }

    /**
     * 就地更新一条<b>未发布草稿</b>版本行（草稿编辑 · 版本管理机制）。
     *
     * <p>⚠️ <b>只允许更新未发布行</b>：实现必须以
     * {@code WHERE published_at IS NULL} 作为更新条件，影响行数为 0 即视为失败 ——
     * 已发布版本是线上生效过的不可变历史（DB 触发器同样封禁）。
     *
     * <p>⚠️ <b>全局口径</b>（不按租户绑定过滤）：与 {@link #findLatestGlobal} 同理 ——
     * 草稿行可能属于尚未绑定任何租户的全局 Agent，按绑定过滤会永远更新不到。
     *
     * @param def 新的草稿内容（agentId + version 用于定位）
     * @return {@code true} = 确有未发布行被更新；{@code false} = 目标行不存在或已发布
     */
    boolean updateDraftGlobal(@NonNull CapAgentDef def);

    /**
     * 把一条已存在的<b>未发布草稿</b>版本行转正（写入 published_at，全局口径）。
     *
     * <p>⚠️ 同 CapToolDefPort#publishDraft：变更提交时版本行已以草稿形态落库，
     * 发布只是把这一版转正；再 insert 会因 ON CONFLICT DO NOTHING 静默 no-op。
     *
     * @param agentId     Agent 标识
     * @param version     版本号
     * @param publishedAt 发布时间
     * @return true = 确有草稿行被转正
     */
    boolean publishDraftGlobal(@NonNull String agentId, int version,
                               @NonNull Instant publishedAt);

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

    /**
     * 列出该 Agent 的<b>全部版本行</b>（不可变历史，按版本倒序，<b>不按租户绑定过滤</b>）。
     *
     * <p>⚠️ 与 {@link #findLatestGlobal(String)} 同口径的「全局」读取 —— 供平台控制台
     * 做版本回溯（能力目录版本管理 · 历史版本留存）；全局 Agent 可以尚无任何
     * {@code tenant_agent_binding}，按绑定过滤会读不到。
     * <b>不</b>用于授权判定中的版本解析（那必须走 {@link PublishedVersionPort}）。
     *
     * @param agentId Agent 逻辑 id（UUID）
     * @return 全部版本行，按 version 倒序；无任何版本时为空列表
     */
    @NonNull List<CapAgentDef> listVersionsGlobal(@NonNull String agentId);
}
