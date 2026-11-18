package cn.studykid.multiagent.worker.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.agentchataudit.ChatAuditRetentionService;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditDirectoryRetentionPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;

/**
 * 对话审计清理任务的行为验证（PIPL §7）。
 *
 * <p><b>本类存在的核心理由</b>：它是「第四次断链」的正面证据。
 * 本项目已三次遇到「机制正确、测试全绿、但不在链路上」
 * （D01、D03、{@code run_message} 的 DELETE 权限给了却无人调用）。
 * 只交付 {@link ChatAuditRetentionService} 而不证明<b>有人调用它</b>，
 * 就会得到第四个形态完全相同的缺陷：原文按承诺「已保留」，
 * 实际从未被删除，且系统无报错。
 *
 * <p><b>三类命题</b>：构造期 fail-closed / 驱动性（{@code purgeOnce} 真调服务）/
 * 韧性（失败计入告警出口、停机后不再清理）。
 */
@DisplayName("对话审计保留期清理任务")
class ChatAuditRetentionJobTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private static ChatAuditRetentionService service(ChatAuditRetentionPort db,
                                                      ChatAuditDirectoryRetentionPort dir) {
        return new ChatAuditRetentionService(db, dir,
                ChatAuditConfig::defaults, null, "retention-job", 100,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ChatAuditRetentionJob job(ChatAuditRetentionService service) {
        return new ChatAuditRetentionJob(service, List.of(TENANT_A), Duration.ofHours(1));
    }

    private static ChatAuditRetentionPort countingPort(AtomicInteger calls, int rows) {
        return (tenantId, cutoff, limit) -> {
            calls.incrementAndGet();
            return rows;
        };
    }

    private static ChatAuditDirectoryRetentionPort noFiles() {
        return (directory, cutoffDate) -> 0;
    }

    @Nested
    @DisplayName("J-1 构造期 fail-closed")
    class ConstructionGuard {

        @Test
        @DisplayName("空租户列表被拒绝（否则实例安静地从不清理）")
        void emptyTenantListRejected() {
            assertThatThrownBy(() -> new ChatAuditRetentionJob(
                    service(countingPort(new AtomicInteger(), 0), noFiles()),
                    List.of(), Duration.ofHours(1)))
                    .as("⚠️ 空列表与「配置错误导致从不清理」表现完全一致，必须在构造期拒绝")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少指定一个租户");
        }

        @Test
        @DisplayName("间隔 <60s 被拒绝（保留期是天级的，分钟级轮询只有压力）")
        void tooShortIntervalRejected() {
            assertThatThrownBy(() -> new ChatAuditRetentionJob(
                    service(countingPort(new AtomicInteger(), 0), noFiles()),
                    List.of(TENANT_A), Duration.ofSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("间隔过小");
        }
    }

    @Nested
    @DisplayName("J-2 驱动性（关键：证明它真的调用了清理服务）")
    class Driven {

        @Test
        @DisplayName("purgeOnce 真的调用端口并累计计数")
        void purgeOnceDrivesService() {
            AtomicInteger calls = new AtomicInteger();
            List<Integer> fileCalls = new ArrayList<>();
            ChatAuditDirectoryRetentionPort dir = (directory, cutoffDate) -> {
                fileCalls.add(1);
                return 4;
            };
            ChatAuditRetentionJob job = job(service(countingPort(calls, 7), dir));

            ChatAuditRetentionService.Report report = job.purgeOnce();

            assertThat(calls.get()).as("⛔ 任务存在但从不调用清理服务（第四次断链）").isEqualTo(1);
            assertThat(fileCalls).hasSize(1);
            assertThat(report.rowsDeleted()).isEqualTo(7);
            assertThat(job.rounds()).isEqualTo(1);
            assertThat(job.totalRowsDeleted()).isEqualTo(7);
            assertThat(job.totalFilesDeleted()).isEqualTo(4);
        }

        @Test
        @DisplayName("⛔ 失败类别被累计到告警出口（否则 PurgeReport 的字段只是装饰）")
        void failedCategoriesAreAccumulated() {
            ChatAuditRetentionPort failing = (tenantId, cutoff, limit) -> {
                throw new IllegalStateException("模拟清理失败");
            };
            ChatAuditRetentionJob job = job(service(failing, noFiles()));

            job.purgeOnce();

            assertThat(job.totalFailedCategories())
                    .as("⛔ 清理失败没人看：静默失败等于长期合规违约")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("停机后 purgeOnce 返回空报告，不再产生跨停机边界的工作")
        void noWorkAfterStop() {
            AtomicInteger calls = new AtomicInteger();
            ChatAuditRetentionJob job = job(service(countingPort(calls, 5), noFiles()));
            job.start();
            job.stop();

            ChatAuditRetentionService.Report report = job.purgeOnce();

            assertThat(report.hasChanges()).isFalse();
            assertThat(job.rounds())
                    .as("⛔ 停机后仍在清理：守护线程可能在已关闭的 DataSource 上操作")
                    .isZero();
        }

        @Test
        @DisplayName("一轮内服务整体抛异常时任务不崩，且计入失败类别")
        void serviceCrashDoesNotKillJob() {
            ChatAuditRetentionService crashing = new ChatAuditRetentionService(
                    (tenantId, cutoff, limit) -> 0, noFiles(),
                    () -> {
                        throw new IllegalStateException("配置表不可读");
                    },
                    null, "retention-job", 100, Clock.fixed(NOW, ZoneOffset.UTC));
            ChatAuditRetentionJob job = job(crashing);

            ChatAuditRetentionService.Report report = job.purgeOnce();

            assertThat(report.hasChanges()).isFalse();
            assertThat(job.rounds()).isEqualTo(1);
            assertThat(job.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("J-3 生命周期")
    class Lifecycle {

        @Test
        @DisplayName("start 后真的跑了一轮（证明调度线程不是摆设）")
        void startRunsAtLeastOneRound() {
            AtomicInteger calls = new AtomicInteger();
            ChatAuditRetentionJob job = job(service(countingPort(calls, 1), noFiles()));
            job.start();
            try {
                assertThat(job.isRunning()).isTrue();
                // 首轮在启动线程内立即执行：轮询等待而不是固定 sleep
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (job.rounds() == 0 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertThat(job.rounds())
                        .as("⛔ 任务启动了但从未执行一轮：Bean 在、线程在、清理没发生")
                        .isEqualTo(1);
                assertThat(calls.get()).isEqualTo(1);
            } finally {
                job.stop();
            }
            assertThat(job.isRunning()).isFalse();
        }

        @Test
        @DisplayName("重复 start 不产生第二个线程")
        void startIsIdempotent() {
            AtomicInteger calls = new AtomicInteger();
            ChatAuditRetentionJob job = job(service(countingPort(calls, 0), noFiles()));
            job.start();
            job.start();
            try {
                assertThat(job.isRunning()).isTrue();
            } finally {
                job.stop();
            }
        }
    }
}
