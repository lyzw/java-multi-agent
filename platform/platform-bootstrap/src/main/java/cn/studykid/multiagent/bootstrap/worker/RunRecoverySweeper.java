package cn.studykid.multiagent.bootstrap.worker;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    public RunRecoverySweeper(RunClaimPort claimPort,
                              RunDispatchConsumer.RunExecutor executor,
                              ObjectProvider<PlatformTenantPort> tenantPort,
                              @Value("${agent.worker.id:worker-local}") String workerId,
                              @Value("${agent.worker.lease-seconds:30}") long leaseSeconds,
                              @Value("${agent.worker.recovery.enabled:true}") boolean enabled,
                              @Value("${agent.worker.recovery.batch-size:50}") int batchSize,
                              @Value("${agent.worker.recovery.tenant-limit:200}") int tenantLimit) {
        this.claimPort = claimPort;
        this.executor = executor;
        this.tenantPort = tenantPort;
        this.workerId = workerId;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.tenantLimit = tenantLimit;
        log.info("崩溃恢复扫描器装配: enabled={}, batchSize={}, tenantLimit={}, 租约={}s",
                enabled, batchSize, tenantLimit, leaseSeconds);
    }

    /**
     * 周期扫描并接管过期 RUNNING（崩溃恢复）。
     *
     * <p>⚠️ fixedDelay：上轮结束后再等一个间隔，避免与长任务执行重叠堆积。
     */
    @Scheduled(fixedDelayString = "${agent.worker.recovery.interval-seconds:30}000")
    public void sweep() {
        if (!enabled) {
            log.debug("崩溃恢复扫描已禁用（agent.worker.recovery.enabled=false），跳过本轮");
            return;
        }
        PlatformTenantPort port = tenantPort.getIfAvailable();
        if (port == null) {
            // ⚠️ 平台运维通道未接入 → 无法枚举租户 → 跳过
            //    （fail-closed：不强行动用未验证的数据源，也不致启动失败）
            log.warn("崩溃恢复扫描跳过：租户枚举端口 PlatformTenantPort 不可用"
                    + "（平台运维通道未接入？），过期 RUNNING 暂无法自动接管");
            return;
        }
        List<PlatformTenantPort.PlatformTenantRow> tenants = port.listTenants("ACTIVE", tenantLimit);
        if (tenants.isEmpty()) {
            return;
        }
        int recovered = 0;
        for (PlatformTenantPort.PlatformTenantRow tenant : tenants) {
            String tenantId = tenant.tenantId();
            try {
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
        if (recovered > 0) {
            log.info("崩溃恢复扫描完成：本轮接管并执行 {} 个过期 RUNNING", recovered);
        }
    }
}
