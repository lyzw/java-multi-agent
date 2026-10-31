package cn.studykid.multiagent.bootstrap.worker;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cn.studykid.multiagent.application.observability.LogContext;
import cn.studykid.multiagent.ports.persistence.PlatformTenantPort;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.worker.dispatch.RunDispatchConsumer;

/**
 * 崩溃恢复扫描器（第二十一批 · 接通 claimTasks 生产调用方）。
 *
 * <p><b>要解决的问题</b>：{@code RunClaimPort.claimTasks} 已能接管过期
 * {@code RUNNING} 租约（SQL 就绪），但<b>生产从未有任何调用方</b>。
 * worker 崩溃后它持有的 RUNNING run 租约过期，却再无人认领 → 永久卡死。
 * 本扫描器周期性枚举活跃租户、调用 {@code claimTasks} 接管过期 RUNNING，
 * 并把结果驱动给与 MQ 主路径<b>完全相同</b>的执行器。
 *
 * <p><b>⚠️ fail-closed 纪律</b>：
 * <ul>
 *   <li>显式开关 {@code agent.worker.recovery.enabled}（默认开启）；关闭则不扫描。</li>
 *   <li>租户枚举端口（{@code PlatformTenantPort}）<b>可选注入</b>：
 *       平台运维通道未接入（端口不可用）时，本扫描器<b>仅 WARN 并跳过</b>，
 *       不强行动用未验证的数据源，也不因缺依赖而启动失败。</li>
 *   <li><b>租户枚举抛异常与「端口不可用」同等对待</b>：
 *       两者都是「恢复链路已死」，都落到 {@link #healthy()} 与
 *       {@link #unhealthyReason()}，日志只保留「首次 + 每 40 轮一次摘要」。
 *       ⚠️ 此前枚举的异常<b>裸奔在 try/catch 之外</b>，
 *       异常一路抛到 Spring 的调度线程包装器（表现为
 *       「Unexpected error occurred in scheduled task」），
 *       而健康状态<b>从未被置位</b> —— 「恢复链路已死」这件事
 *       <b>没有落到任何可告警的地方</b>，健康检查端点照样报 healthy。
 *       （实测 2026-10-31：app_agent 无 platform schema USAGE，
 *       本扫描器每 30 秒抛一次权限错，崩溃恢复全程静默失效。）</li>
 *   <li>单租户处理异常<b>不影响</b>其他租户（隔离故障域）。</li>
 * </ul>
 *
 * <p><b>⚠️ 与租约续期的边界</b>：本扫描器只接管<b>租约已过期</b>的 RUNNING
 * （{@code claimTasks} 的 SQL 已含 {@code lease_expires_at < now()}），
 * fencing token 保证旧世代的提交被拒，不会与仍在跑的旧 worker 产生双写。
 */
@Component
public class RunRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RunRecoverySweeper.class);

    private final RunClaimPort claimPort;
    private final RunDispatchConsumer.RunExecutor executor;
    private final ObjectProvider<PlatformTenantPort> tenantPort;

    private final String workerId;
    private final Duration lease;
    private final boolean enabled;
    private final int batchSize;
    private final int tenantLimit;

    /**
     * 租户枚举端口<b>连续不可用</b>的轮次数。
     *
     * <p>⚠️ 这个计数本身就是事故信号：一旦非零，说明「崩溃恢复」这条链路
     * 已经<b>事实上不工作</b> —— worker 崩溃留下的 RUNNING 无人接管，
     * 而系统表面一切正常。故它不是给日志用的计数值，
     * 是给 {@link #healthy()} 用的健康事实。
     */
    private volatile int portUnavailableStreak;

    /**
     * 健康状态：{@code false} 表示本轮起崩溃恢复<b>未真正生效</b>。
     *
     * <p>⚠️ <b>为什么必须显式维护它</b>：此前端口不可用时只有一行 WARN，
     * 每 30 秒重复一次。重复 2880 次/天的同文案 WARN 会被
     * 日志告警规则当噪音滤掉 —— <b>等同于静默</b>。
     * 「有日志」不等于「可被察觉」，更不等于「可被告警」。
     */
    private volatile String unhealthyReason;

    /**
     * 是否要求租户枚举端口<b>必须可用</b>（P3-1 ⑤）。
     *
     * <p>⚠️ 默认 {@code false}：保留「缺依赖也不阻塞启动」的既有取舍
     * （这条依赖来自平台运维通道，不是所有环境都接入）。
     * 置为 {@code true} 的部署环境中，端口缺失即<b>拒绝维持扫描线程</b>，
     * 让「恢复链路已死」在第一次调度时就炸出来，而不是等到真崩溃那天。
     */
    private final boolean requireTenantPort;

    /**
     * 挂起（等待人工审批）Run 的最长允许时长（M1-4 / G2 · 防挂起泄漏兜底）。
     *
     * <p>⚠️ <b>默认必须大于审批 TTL</b>：审批行本身有有效期（{@code agent.approval.ttl}，
     * 当前 15 分钟）。若这里的超时比 TTL 短，Run 会在人还有机会批之前就被判失败；
     * 若太长，挂起泄漏的暴露窗口就变长。取 30 分钟 = TTL 的两倍，
     * 给人留出「看到待办 → 处理 → 决定到达」的窗口。
     */
    private final Duration parkedTimeout;

    public RunRecoverySweeper(RunClaimPort claimPort,
                              RunDispatchConsumer.RunExecutor executor,
                              ObjectProvider<PlatformTenantPort> tenantPort,
                              @Value("${agent.worker.id:worker-local}") String workerId,
                              @Value("${agent.worker.lease-seconds:30}") long leaseSeconds,
                              @Value("${agent.worker.recovery.enabled:true}") boolean enabled,
                              @Value("${agent.worker.recovery.batch-size:50}") int batchSize,
                              @Value("${agent.worker.recovery.tenant-limit:200}") int tenantLimit,
                              @Value("${agent.worker.recovery.require-tenant-port:false}")
                              boolean requireTenantPort,
                              @Value("${agent.worker.recovery.parked-timeout-seconds:1800}")
                              long parkedTimeoutSeconds) {
        this.claimPort = claimPort;
        this.executor = executor;
        this.tenantPort = tenantPort;
        this.workerId = workerId;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.tenantLimit = tenantLimit;
        this.requireTenantPort = requireTenantPort;
        this.parkedTimeout = Duration.ofSeconds(parkedTimeoutSeconds);
        log.info("崩溃恢复扫描器装配: enabled={}, batchSize={}, tenantLimit={}, 租约={}s, "
                        + "requireTenantPort={}, 挂起超时={}s",
                enabled, batchSize, tenantLimit, leaseSeconds, requireTenantPort,
                parkedTimeoutSeconds);
    }

    /**
     * 健康状态（供健康检查端点/外部探针读取）。
     *
     * @return 已生效返回 {@code null}；未生效返回人读原因（<b>绝不为空串</b>）
     */
    public String unhealthyReason() {
        return unhealthyReason;
    }

    /** 租户枚举端口连续不可用的轮次数（{@code 0} = 本轮正常）。 */
    public int portUnavailableStreak() {
        return portUnavailableStreak;
    }

    /**
     * 健康检查用的一句话结论。
     *
     * <p>⚠️ 这里刻意不返回 {@code Health} 类型：本类在 worker 包，
     * 不应为了「把恢复到 actuators 里」而引入 actuator 的编译期依赖 ——
     * 装配侧的 {@code HealthIndicator} 读本方法即可。
     */
    public boolean healthy() {
        return unhealthyReason == null;
    }

    private void markHealthy() {
        if (unhealthyReason != null) {
            log.info("崩溃恢复链路已恢复健康（此前原因：{}）", unhealthyReason);
        }
        unhealthyReason = null;
        portUnavailableStreak = 0;
    }

    /**
     * 周期扫描并接管过期 RUNNING（崩溃恢复）。
     *
     * <p>⚠️ fixedDelay：上轮结束后再等一个间隔，避免与长任务执行重叠堆积。
     */
    @Scheduled(fixedDelayString = "${agent.worker.recovery.interval-seconds:30}000")
    public void sweep() {
        // 定时线程既没有 HTTP 过滤器也没有 MQ 消息头，MDC 全空。
        // 这里补一条「本轮扫描」的系统链路：恢复执行会走完整 Agent 调用路径，
        // 没有它，崩溃恢复产生的日志与真实请求日志在链路维度上是两类无法区分的东西。
        try (LogContext.Scope round = LogContext.push(new LogContext.Snapshot(
                LogContext.newTraceId(), null, LogContext.SYSTEM))) {
            doSweep();
        }
    }

    private void doSweep() {
        if (!enabled) {
            log.debug("崩溃恢复扫描已禁用（agent.worker.recovery.enabled=false），跳过本轮");
            return;
        }
        PlatformTenantPort port = tenantPort.getIfAvailable();
        if (port == null) {
            // ── P3-1 ⑤：由「每轮一行 WARN」升级为「健康降级 + 抑制切换」 ──
            //    ⚠️ 以前每 30 秒打一行相同的 WARN：一天 2880 行。
            //    这种重复噪音会被告警规则当背景滤液掉 —— **有日志 ≠ 可被察觉**，
            //    实际效果与静默关闭崩溃恢复没有区别，而崩溃恢复恰恰是
            //    「worker 崩了之后谁来接手」的唯一答案。
            //    故：状态落到 healthy()（可被健康检查端点读取与告警），
            //    日志只保留「首次 + 每 40 轮一次摘要」，既不刷屏也不丢最高优先级那一次。
            String reason = "租户枚举端口 PlatformTenantPort 不可用"
                    + "（平台运维通道未接入？），过期 RUNNING 无法自动接管";
            boolean firstFailure = portUnavailableStreak == 0;
            portUnavailableStreak++;
            unhealthyReason = reason;
            if (firstFailure) {
                log.error("⚠️⚠️ 崩溃恢复链路不可用：{}（连续失败 1 轮；"
                        + "worker 崩溃后遗留的 RUNNING 将无人接管，属静默失效）", reason);
            } else if (portUnavailableStreak % 40 == 0) {
                log.error("⚠️⚠️ 崩溃恢复链路持续不可用：{}（已连续失败 {} 轮）",
                        reason, portUnavailableStreak);
            }
            if (requireTenantPort) {
                // fail-closed：本环境声明了「没有恢复能力就不该继续跑」
                throw new IllegalStateException("崩溃恢复链路不可用且已配置 "
                        + "agent.worker.recovery.require-tenant-port=true，"
                        + "拒绝继续扫描（让故障在调度线程可被观测，而非留到真实崩溃那天）: "
                        + reason);
            }
            return;
        }
        // ⚠️⚠️ 租户枚举**可能抛异常**（权限不足 / 连接断 / SQL 错误）——
        //    此前它裸奔在 try/catch 之外：异常一路抛到 Spring 的调度线程包装器，
        //    表现为「Unexpected error occurred in scheduled task」，
        //    而本类的健康状态（unhealthyReason）**从未被置位** ——
        //    即「恢复链路已死」这件事**没有落到任何可告警的地方**，
        //    健康检查端点照样报 healthy。
        //    （实测 2026-10-31：app_agent 无 platform schema USAGE，
        //      RunRecoverySweeper 每 30 秒抛一次权限错，崩溃恢复全程静默失效。）
        //    故这里与「端口不可用」走**同一条降级路径**：置健康状态 + 抑制重复刷屏。
        List<PlatformTenantPort.PlatformTenantRow> tenants;
        try {
            tenants = port.listTenants("ACTIVE", tenantLimit);
        } catch (RuntimeException e) {
            String reason = "租户枚举失败（PlatformTenantPort.listTenants 抛异常），"
                    + "过期 RUNNING 无法自动接管: " + e;
            boolean firstFailure = portUnavailableStreak == 0;
            portUnavailableStreak++;
            unhealthyReason = reason;
            if (firstFailure) {
                log.error("⚠️⚠️ 崩溃恢复链路不可用：{}（连续失败 1 轮；"
                        + "worker 崩溃后遗留的 RUNNING 将无人接管，属静默失效）", reason);
            } else if (portUnavailableStreak % 40 == 0) {
                log.error("⚠️⚠️ 崩溃恢复链路持续不可用：{}（已连续失败 {} 轮）",
                        reason, portUnavailableStreak);
            }
            if (requireTenantPort) {
                throw new IllegalStateException("崩溃恢复链路不可用且已配置 "
                        + "agent.worker.recovery.require-tenant-port=true，"
                        + "拒绝继续扫描（让故障在调度线程可被观测，而非留到真实崩溃那天）: "
                        + reason, e);
            }
            return;
        }
        markHealthy();
        if (tenants.isEmpty()) {
            return;
        }
        int recovered = 0;
        for (PlatformTenantPort.PlatformTenantRow tenant : tenants) {
            String tenantId = tenant.tenantId();
            // 按租户切上下文：恢复执行是整条 Agent 调用链，日志必须说清在为哪个租户跑。
            // ⚠️ catch 在作用域内部（作用域先于 catch 关闭，写在外会丢租户字段）。
            try (LogContext.Scope ignored = LogContext.push(new LogContext.Snapshot(
                    LogContext.traceId(), tenantId, LogContext.SYSTEM))) {
                try {
                    // ⚠️ M1-4 / G2：**挂起超时兜底**。
                    //    PARK 后的 Run 不占线程、不续租 —— 若审批决定始终没来，
                    //    它会永久停在 WAITING_APPROVAL（不报错、不告警、只是不再动）。
                    //    这里把它收敛为 FAILED（工具未执行，无副作用）。
                    int timedOut = claimPort.failStaleParkedRuns(tenantId, parkedTimeout);
                    if (timedOut > 0) {
                        log.warn("本轮收敛挂起超时的 Run {} 个（等待审批超过 {}s）",
                                timedOut, parkedTimeout.toSeconds());
                    }
                    List<RunClaimPort.LeasedTask> tasks =
                            claimPort.claimTasks(tenantId, workerId, lease, batchSize);
                    for (RunClaimPort.LeasedTask task : tasks) {
                        recovered++;
                        try {
                            executor.execute(tenantId, task);
                        } catch (RuntimeException e) {
                            // ⚠️ 执行器已内部兜底收敛（commit 失败=白干，不抛），
                            // 此处仅记录，不中断其他租户的恢复。
                            log.error("恢复执行异常（已接管，交由对账/重投）: runId={}, tenant={}",
                                    task.runId(), tenantId, e);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("恢复扫描单租户失败（跳过该租户）: tenant={}, cause={}",
                            tenantId, e.toString());
                }
            }
        }
        if (recovered > 0) {
            log.info("崩溃恢复扫描完成：本轮接管并执行 {} 个过期 RUNNING", recovered);
        }
    }
}
