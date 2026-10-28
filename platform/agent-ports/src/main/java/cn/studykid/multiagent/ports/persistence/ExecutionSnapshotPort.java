package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 执行快照解析端口（修复 {@code POST /api/runs} 恒 500）。
 *
 * <p><b>为什么需要它</b>：{@code run.snapshot_id} 上有外键
 * {@code run_snapshot_fk → execution_snapshot(tenant_id, version_id)}。
 * 此前 {@code RunController.CurrentPrincipal.snapshotId()} 直接
 * {@code UUID.randomUUID()}，于是<b>每一次</b>建 Run 都必然违反外键 →
 * 端点恒返回 500，且失败现场指向「服务端故障」而非「参数无效」。
 * 该行为曾被记为「P2-D05 §6 L5 · 暂不触发」，但外键是无条件的，
 * 「不触发」这一判断本身是错的。
 *
 * <p><b>解析规则</b>：{@code agentId} → 该 Agent <b>已发布</b>的最高版本
 * （{@code agent_version.published_at IS NOT NULL}）→ 对应的
 * {@code execution_snapshot.version_id}。
 * 「未发布」与「无快照」都属<b>客户端错误</b>（4xx），不是服务端故障。
 * 该最高版本的 {@code version_number} 与快照<b>同一次读</b>返回，
 * 供 {@code execution_capability_snapshot.agent_version} 使用（G-3 写入链路）。
 *
 * <p><b>⚠️ 必须在写事务内调用</b>：否则解析与写入之间存在窗口，
 * Agent 恰好在此期间被改版，Run 会挂到一个已不可用的配置快照上。
 * 嵌套调用 {@code TransactionalConnectionHolder.inTransaction} 会复用外层连接，
 * 故与 {@code RunCreationService} 的写入事务自动同事务。
 *
 * <p><b>⚠️ 必须用 {@link Optional} 而非抛异常或返回 null</b>：
 * 「查不到」是<b>正常业务结果</b>（Agent 未发布），不是故障。
 * 抛异常会让调用方分不清「没有」与「挂了」，进而把 4xx 报成 5xx。
 */
public interface ExecutionSnapshotPort {

    /**
     * 解析指定 Agent 当前生效的执行快照。
     *
     * <p>⚠️ <b>返回的是「快照 + 版本」二元组，不是单个 id</b>：
     * 两者来自<b>同一条 SQL 的同一次读</b>。若拆成两次查询，
     * 中间 Agent 恰好改版就会得到「快照是 v3、版本号是 v4」的错配，
     * 而这类错配一旦写进 {@code execution_capability_snapshot.agent_version}
     * 就是永久失真 —— 事后无法解释「当时到底按哪版配置跑的」。
     *
     * @param tenantId 租户 ID（来自认证上下文，<b>不得</b>取自请求体）
     * @param agentId  Agent ID（来自请求体）
     * @return 快照引用；Agent 不存在 / 未发布 / 无快照时返回 {@link Optional#empty()}
     */
    @NonNull
    Optional<SnapshotRef> resolveSnapshot(@NonNull String tenantId, @NonNull String agentId);

    /**
     * 执行快照引用：{@code run.snapshot_id} 要绑的快照主键 + 其所属的 Agent 版本号。
     *
     * @param snapshotId   {@code execution_snapshot.snapshot_id}（自身主键，<b>不是</b> version_id）
     * @param agentVersion {@code agent_version.version_number}，正整数
     */
    record SnapshotRef(@NonNull String snapshotId, int agentVersion) {

        public SnapshotRef {
            if (snapshotId == null || snapshotId.isBlank()) {
                throw new IllegalArgumentException("snapshotId 不能为空");
            }
            if (agentVersion <= 0) {
                throw new IllegalArgumentException("agentVersion 必须为正数，收到: " + agentVersion);
            }
        }
    }
}