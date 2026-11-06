package cn.studykid.multiagent.worker.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.retention.RetentionPurgeService;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.ports.persistence.ErasurePort.ErasureCertificate;
import cn.studykid.multiagent.ports.persistence.ErasurePort.PendingWithdrawal;

/**
 * 保留期清理任务行为测试（P4-D04）。
 *
 * <p><b>本类存在的核心理由</b>：它是「第三次断链」的正面证据。
 * 本项目已两次遇到「机制正确但没有调用方」——
 * 因此不仅要有 {@code RetentionJob} 这个类，
 * 还要有测试证明<b>它调用别人、也能被别人调用</b>。
 *
 * <p><b>三类命题</b>：
 * <ol>
 *   <li><b>构造期 fail-closed</b>：空租户列表、零/过短间隔一律拒绝；</li>
 *   <li><b>驱动性</b>（关键）：{@code purgeOnce()} 真的调用了清理服务；</li>
 *   <li><b>韧性</b>：单租户失败不影响其余租户；停机后不再清理。</li>
 * </ol>
 *
 * <p><b>不启真实线程</b>：只调用 {@code purgeOnce()}，避免 sleep 式 flaky 测试。
 * {@code start()/stop()} 的生命周期由 {@code RetentionWiringTest}（Spring）覆盖。
 */
@DisplayName("P4-D04 保留期清理任务行为")
class RetentionJobTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    @Nested
    @DisplayName("J-1 构造期 fail-closed")
    class ConstructionGuard {

        @Test
        @DisplayName("J-1a ⚠️ 空租户列表被拒绝（否则实例安静地从不清理）")
        void emptyTenantListRejected() {
            assertThatThrownBy(() -> new RetentionJob(service(new StubPort()),
                    List.of(), Duration.ofHours(1)))
                    .as("⚠️ 空列表与「配置错误导致从不清理」表现完全一致，"
                            + "必须在构造期就拒绝")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少指定一个租户");
        }

        @Test
        @DisplayName("J-1b 间隔为 0 被拒绝（0 = 忙等打满数据库）")
        void zeroIntervalRejected() {
            assertThatThrownBy(() -> new RetentionJob(service(new StubPort()),
                    List.of(TENANT_A), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("J-1c 间隔小于 60s 被拒绝（保留期是天/月级，分钟级无收益）")
        void tooSmallIntervalRejected() {
            assertThatThrownBy(() -> new RetentionJob(service(new StubPort()),
                    List.of(TENANT_A), Duration.ofSeconds(30)))
                    .as("分钟级轮询只增加数据库压力，不提升合规性")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("60");
        }

        @Test
        @DisplayName("J-1d 合规参数通过（不误伤）")
        void validConstructionAccepted() {
            RetentionJob job = new RetentionJob(service(new StubPort()),
                    List.of(TENANT_A), Duration.ofHours(1));
            assertThat(job.isRunning()).isFalse();
            assertThat(job.rounds()).isZero();
        }
    }

    @Nested
    @DisplayName("J-2 ⚠️ 驱动性：purgeOnce 真的调用清理服务")
    class DrivesPurgeService {

        @Test
        @DisplayName("J-2a ⚠️⚠️ purgeOnce 必须使清理服务被调用（否则=第三次断链）")
        void purgeOnceInvokesService() {
            StubPort port = new StubPort();
            RetentionJob job = new RetentionJob(service(port),
                    List.of(TENANT_A), Duration.ofHours(1));

            job.purgeOnce();

            assertThat(port.purgeCalls.get())
                    .as("⚠️⚠️ RetentionPurgeService.runOnce 必须被调用——"
                            + "清理服务本身不会自我驱动")
                    .isEqualTo(1);
            assertThat(job.rounds()).isEqualTo(1);
        }

        @Test
        @DisplayName("J-2b 多租户逐个清理，且轮数只记一次")
        void allTenantsPurged() {
            StubPort port = new StubPort();
            RetentionJob job = new RetentionJob(service(port),
                    List.of(TENANT_A, TENANT_B), Duration.ofHours(1));

            job.purgeOnce();

            assertThat(port.purgeCalls.get()).isEqualTo(2);
            assertThat(port.seenTenants).containsExactly(TENANT_A, TENANT_B);
            assertThat(job.rounds()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("J-3 韧性与失败可见性")
    class Resilience {

        @Test
        @DisplayName("J-3a ⚠️ 单个租户失败不终止其余租户（否则集体违约）")
        void failureDoesNotStopOthers() {
            StubPort port = new StubPort();
            port.failOnTenant = TENANT_A;
            RetentionJob job = new RetentionJob(service(port),
                    List.of(TENANT_A, TENANT_B), Duration.ofHours(1));

            job.purgeOnce();

            assertThat(port.seenTenants)
                    .as("⚠️ A 坏了也要继续清 B——一个租户的故障不能让另一个租户违约")
                    .containsExactly(TENANT_A, TENANT_B);
            assertThat(job.totalFailedCategories())
                    .as("失败必须被计数，否则「清理成功」是假象")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("J-3b 清理类别失败时失败计数被消费（非装饰性字段）")
        void categoryFailureCounted() {
            StubPort port = new StubPort();
            port.blankPromptsThrows = true;
            RetentionJob job = new RetentionJob(service(port),
                    List.of(TENANT_A), Duration.ofHours(1));

            job.purgeOnce();

            assertThat(job.totalFailedCategories())
                    .as("PurgeReport.failedCategories 若无人使用就是装饰性字段，"
                            + "本用例证明它真的被消费")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("J-3c 停机后 purgeOnce 不再清理（优雅停机语义）")
        void purgesNothingAfterStop() {
            StubPort port = new StubPort();
            RetentionJob job = new RetentionJob(service(port),
                    List.of(TENANT_A), Duration.ofHours(1));

            job.purgeOnce();
            int before = port.purgeCalls.get();

            job.stop();
            job.purgeOnce();

            assertThat(port.purgeCalls.get())
                    .as("停机窗口内不得再产生新的清理工作")
                    .isEqualTo(before);
        }
    }

    private static RetentionPurgeService service(ErasurePort port) {
        return new RetentionPurgeService(
                port,
                new RetentionPurgeService.RetentionSettings(12, 12, 90, 180, 30, 0, 1000),
                "retention-job",
                Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("J-4 ⚠️ 停机必须等待在途轮次完成（修复 shutdown race）")
    class StopJoinsInFlightThread {

        @Test
        @DisplayName("J-4a ⚠️⚠️ stop() 阻塞等待在途清理轮次结束、线程才退出"
                + "（否则守护线程会在 DataSource 关闭后命中已关闭的池）")
        void stopWaitsForInFlightRoundThenThreadExits() throws Exception {
            // ⚠️ 用「标志位 + 忙等」模拟在途 DB 操作：JDBC getConnection 不可被
            //    interrupt 直接打断，故忽略中断、只在标志位置位后退出。
            final boolean[] released = {false};
            StubPort port = new StubPort() {
                @Override
                public int purgeExpiredProfiles(String tenantId, Instant cutoff, int limit) {
                    purgeCalls.incrementAndGet();
                    // ⚠️ JDBC 调用本就不响应中断：用忙等模拟「在途阻塞的 DB 操作」，
                    //    且<b>保留</b>中断状态——stop() 的 interrupt 必须由循环尾的
                    //    Thread.sleep(interval) 真正收到并让线程退出（这正是生产行为）。
                    //    若像 R-7 那样吞掉中断，则 1 小时间隔的 sleep 会跑满，join 超时。
                    while (!released[0]) {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            // 保留中断状态，待在途调用结束后交还给循环尾的 sleep 处理
                            Thread.currentThread().interrupt();
                        }
                    }
                    return 1;
                }
            };
            RetentionJob job = new RetentionJob(service(port),
                    List.of(TENANT_A), Duration.ofHours(1));
            job.start();

            // 轮询确认 worker 已进入在途轮次（purgeExpiredProfiles 已开始阻塞）
            for (int i = 0; i < 100 && port.purgeCalls.get() == 0; i++) {
                Thread.sleep(10);
            }
            assertThat(port.purgeCalls.get())
                    .as("工作线程必须已进入在途清理轮次")
                    .isPositive();

            // 独立线程稍后释放：证明 stop() 会真正阻塞等待，而非立即返回
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                    // 无视
                }
                released[0] = true;
            });
            releaser.setDaemon(true);
            releaser.start();

            long startNanos = System.nanoTime();
            job.stop(); // ⚠️ 修复前：立即返回，线程仍阻塞在在途调用上（仍存活）
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertThat(elapsedMs)
                    .as("⚠️ stop() 必须等待在途轮次（约 120ms），"
                            + "若立即返回即为未 join 的旧缺陷")
                    .isGreaterThan(60);
            assertThat(job.isWorkerAlive())
                    .as("⚠️⚠️ 停机后工作线程必须已退出，"
                            + "否则会在已关闭的 DataSource 上执行 DB 操作")
                    .isFalse();
        }
    }

    /**
     * 端口桩：记录被调用情况。
     *
     * <p>⚠️ 刻意<b>不用 Mockito</b>：本类要断言的是「调用是否发生」，
     * 手写计数比 mock 的 verify 更难误写，且不需要额外依赖。
     */
    private static class StubPort implements ErasurePort {

        final AtomicInteger purgeCalls = new AtomicInteger();
        final java.util.List<String> seenTenants = new java.util.ArrayList<>();
        String failOnTenant;
        boolean blankPromptsThrows;

        private int nextProfileRows = 2;

        @Override
        public int purgeExpiredProfiles(String tenantId, Instant cutoff, int limit) {
            purgeCalls.incrementAndGet();
            seenTenants.add(tenantId);
            if (tenantId.equals(failOnTenant)) {
                throw new IllegalStateException("模拟该租户清理失败");
            }
            return nextProfileRows;
        }

        @Override
        public int purgeExpiredMemories(String tenantId, Instant cutoff, int limit) {
            return 0;
        }

        @Override
        public int blankExpiredPrompts(String tenantId, Instant cutoff, int limit) {
            if (blankPromptsThrows) {
                // ⚠️ 让 PurgeService 的 try/catch 记录一次失败类别
                throw new IllegalStateException("模拟 Prompt 清理失败");
            }
            return 0;
        }

        @Override
        public int purgeExpiredTraces(String tenantId, Instant cutoff, int limit) {
            return 0;
        }

        @Override
        public int purgeExpiredAudits(String tenantId, Instant cutoff, int limit, String actor) {
            return 0;
        }

        @Override
        public void registerWithdrawal(String tenantId, String studentId,
                                       Instant withdrawnAt, Instant dueAt) {
            // 无状态
        }

        @Override
        public List<PendingWithdrawal> findDueWithdrawals(String tenantId, Instant now) {
            return List.of();
        }

        @Override
        public int eraseWithdrawnStudent(String tenantId, String studentId, String actor) {
            return 0;
        }

        @Override
        public void markWithdrawalErased(String tenantId, String studentId, Instant erasedAt) {
            // 无状态
        }

        @Override
        public void markWithdrawalFailed(String tenantId, String studentId) {
            // 无状态
        }

        @Override
        public void recordCertificate(String tenantId, ErasureCertificate cert) {
            // 无状态
        }

        @Override
        public List<ErasureCertificate> findCertificates(String tenantId, String reason, int limit) {
            return List.of();
        }

        /** 便于将来扩展明细断言。 */
        Map<String, Integer> emptyDetail() {
            return Map.of();
        }
    }
}
