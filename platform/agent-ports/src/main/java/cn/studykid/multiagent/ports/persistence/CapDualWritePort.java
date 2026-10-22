package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 迁移双写端口（P0-T6 · 06 §3.3）。
 *
 * <p><b>双写是什么</b>：在旧模型（{@code agent} / {@code agent_version} /
 * {@code execution_snapshot}）写入的同时，同步写入新模型
 * （{@code cap_agent_def} / {@code execution_capability_snapshot}）。
 * 两者<b>并存但指向同一发布事实</b>（06 §1.4）。
 *
 * <p><b>为什么不直接切读路径</b>：新模型在 P0 阶段只有结构、没有语义
 * （解析校验属 P1、发布 CAS 属 P5）。先双写、比对、确认一致，再切读路径，
 * 是 06 §3.3 规定的 7 步迁移流程。
 *
 * <p>⚠️ <b>实现必须 fail-soft</b>：双写失败<b>不得</b>让旧路径的写入失败。
 * 理由：旧路径是当前的生产真源，让它因「顺带写一份新表」而失败，
 * 等于用「迁移期的附加动作」去打断「正在服务的业务」。
 * 失败必须<b>可观测</b>（日志 + 计数），并由比对工具（P0-T7）收敛。
 */
public interface CapDualWritePort {

    /**
     * Agent 版本发布后的双写。
     *
     * @param tenantId      租户标识
     * @param agentId       Agent 标识
     * @param versionNumber 版本号
     * @param definition    版本定义（JSON 文本，自由 schema）
     * @param agentName     Agent 名称（definition 无 name 时的回退）
     * @param agentDescription Agent 描述（definition 无 description 时的回退）
     * @param publishedAt   发布时间
     */
    void onAgentVersionPublished(@NonNull String tenantId,
                                 @NonNull String agentId,
                                 int versionNumber,
                                 @Nullable String definition,
                                 @Nullable String agentName,
                                 @Nullable String agentDescription,
                                 @Nullable Instant publishedAt);

    /**
     * 执行快照创建后的双写。
     *
     * @param tenantId     租户标识
     * @param runId        Run 标识
     * @param agentId      Agent 标识
     * @param agentVersion Agent 定义版本
     * @param principalId  主体标识 —— {@code execution_capability_snapshot.authz_version}
     *                     记录的是「快照生成时该主体的授权版本」，
     *                     故必须知道主体是谁（P1 补齐该字段时加入）
     */
    void onExecutionSnapshotCreated(@NonNull String tenantId,
                                    @NonNull String runId,
                                    @NonNull String agentId,
                                    int agentVersion,
                                    @NonNull String principalId);
}
