package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Agent 发布归档端口（2026-11-09 · 权威源定 A）。
 *
 * <p>⚠️ <b>为什么需要它</b>：权威源定为 {@code cap_agent_def}（结构化真源）后，
 * {@code run.snapshot_id} 的外键链仍是
 * {@code run → execution_snapshot → agent_version}：
 * <pre>
 *   run_snapshot_fk          FOREIGN KEY (tenant_id, snapshot_id)
 *                              REFERENCES execution_snapshot (tenant_id, snapshot_id)
 *   execution_snapshot_version_fk FOREIGN KEY (tenant_id, version_id)
 *                              REFERENCES agent_version (tenant_id, version_id)
 * </pre>
 * 因此「只写 {@code cap_agent_def}」会让<b>建 Run 直接失败</b>（外键违约）——
 * 这正是本轮要修的 P0 的另一半：治理通道发布的 Agent 连 Run 都建不出来。
 *
 * <p>⚠️ <b>本端口写的是「归档」，不是「真源」</b>：
 * <ul>
 *   <li>{@code agent_version.definition} 由 {@code cap_agent_def} <b>派生</b>，
 *       <b>不由客户端提交</b> —— 客户端提交的 JSON 不再有任何效力；</li>
 *   <li>{@code execution_snapshot} 记录该 release 的运行期容量配置与
 *       {@code context_policy} 规范快照（ADR-013 / CH-9），
 *       供 {@code run.snapshot_id} 外键、回放与压缩策略审计使用；</li>
 *   <li>运行期的<b>提示词 / 模型 / 生成参数</b>一律读 {@code cap_agent_def}，
 *       不读归档里的 {@code definition}。</li>
 * </ul>
 *
 * <p>⚠️ <b>幂等</b>：同一 {@code (tenantId, agentId, version)} 已归档时
 * 不覆盖既有行（不可变版本行），返回 {@code false}。
 * 归档与真源在<b>同一事务</b>内写入，故不存在「归档了但真源没落」的中间态。
 */
public interface AgentReleaseArchivePort {

    /**
     * 归档一次 Agent 发布（写 {@code agent_version} + {@code execution_snapshot}）。
     *
     * @param tenantId    租户标识；PLATFORM 作用域发布全局 Agent 时为 {@code null}
     *                    （仓储内部用零 UUID 哨兵绑定 RLS 上下文，归档表已全局化）
     * @param agentId     Agent 标识
     * @param version     版本号（与 {@code cap_agent_def.version} 一致）
     * @param definition    归档用的定义 JSON 文本（<b>由 {@code cap_agent_def} 派生</b>）
     * @param agentConfig   归档用的 Agent 配置 JSON 文本（可空 → {@code {}}）
     * @param contextPolicy 发布期规范化的 {@code generation_params.context} JSON 文本
     *                      （{@code {}} = 未声明 / 回落默认；不可空）
     * @return {@code true} = 本次写入；{@code false} = 该版本已归档（未覆盖）
     */
    boolean archiveRelease(@Nullable String tenantId,
                           @NonNull String agentId,
                           int version,
                           @NonNull String definition,
                           @Nullable String agentConfig,
                           @NonNull String contextPolicy);
}
