package cn.studykid.multiagent.application.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Sinks;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Sinks.FileSink;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditDirectoryRetentionPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRetentionPort;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.ports.persistence.ErasurePort.ErasureCertificate;
import cn.studykid.multiagent.ports.persistence.ErasurePort.PendingWithdrawal;

/**
 * 对话审计保留期清理服务的行为验证（PIPL §7）。
 *
 * <p><b>为什么必须测这个类</b>：它是「原文按承诺删除」这句话的唯一执行者。
 * 本项目已三次遇到「机制存在但不在链路上」，因此本类不仅要证明
 * <b>「调了端口」</b>，还要证明<b>「用的是配置页上的天数」</b>——
 * 若窗口偷偷来自 yml，页面上写 7 天、实际按 90 天删，谁也无法解释。
 */
@DisplayName("对话审计保留期清理服务")
class ChatAuditRetentionServiceTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private static ChatAuditConfig withDays(int days, String directory) {
        ChatAuditConfig d = ChatAuditConfig.defaults();
        Sinks sinks = new Sinks(d.sinks().database(),
                new FileSink(true, directory, "day", false));
        return new ChatAuditConfig(d.enabled(), d.scope(), sinks, d.capture(), d.redaction(),
                d.sampling(), d.writer(), new ChatAuditConfig.Retention(days), d.limits());
    }

    private static ChatAuditRetentionService service(ChatAuditRetentionPort db,
                                                      ChatAuditDirectoryRetentionPort dir,
                                                      int days,
                                                      ErasurePort erasure) {
        return new ChatAuditRetentionService(db, dir, () -> withDays(days, "/data/audit"),
                erasure, "retention-job", 500, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 记录调用参数的库内清理端口。 */
    private static final class RecordingPort implements ChatAuditRetentionPort {
        final List<String> tenants = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        Instant lastCutoff;
        Integer lastLimit;
        int rows = 3;

        @Override
        public int purgeExpired(@NonNull String tenantId, @NonNull Instant cutoff, int limit) {
            calls.incrementAndGet();
            tenants.add(tenantId);
            lastCutoff = cutoff;
            lastLimit = limit;
            return rows;
        }
    }

    /** 记录调用的目录清理端口。 */
    private static final class RecordingDirectory implements ChatAuditDirectoryRetentionPort {
        String lastDirectory;
        LocalDate lastCutoff;
        int files = 2;

        @Override
        public int purgeOlderThan(@NonNull String directory, @NonNull LocalDate cutoffDate) {
            lastDirectory = directory;
            lastCutoff = cutoffDate;
            return files;
        }
    }

    /** 只实现凭证相关方法的擦除端口。 */
    private static final class CertificateOnlyErasure implements ErasurePort {
        final List<ErasureCertificate> certificates = new ArrayList<>();
        final List<String> tenants = new ArrayList<>();

        @Override
        public void recordCertificate(@NonNull String tenantId,
                                      @NonNull ErasureCertificate certificate) {
            tenants.add(tenantId);
            certificates.add(certificate);
        }

        @Override
        public int purgeExpiredProfiles(@NonNull String tenantId, @NonNull Instant cutoff,
                                        int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeExpiredMemories(@NonNull String tenantId, @NonNull Instant cutoff,
                                        int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int blankExpiredPrompts(@NonNull String tenantId, @NonNull Instant cutoff,
                                       int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeExpiredTraces(@NonNull String tenantId, @NonNull Instant cutoff,
                                      int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeExpiredMessages(@NonNull String tenantId, @NonNull Instant cutoff,
                                        int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeExpiredAudits(@NonNull String tenantId, @NonNull Instant cutoff,
                                      int limit, @NonNull String actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerWithdrawal(@NonNull String tenantId, @NonNull String studentId,
                                       @NonNull Instant withdrawnAt, @NonNull Instant dueAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull List<PendingWithdrawal> findDueWithdrawals(@NonNull String tenantId,
                                                                   @NonNull Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int eraseWithdrawnStudent(@NonNull String tenantId, @NonNull String studentId,
                                         @NonNull String actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markWithdrawalErased(@NonNull String tenantId, @NonNull String studentId,
                                         @NonNull Instant erasedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markWithdrawalFailed(@NonNull String tenantId, @NonNull String studentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull List<ErasureCertificate> findCertificates(@NonNull String tenantId,
                                                                  @NonNull String reason,
                                                                  int limit) {
            throw new UnsupportedOperationException();
        }
    }

    @Nested
    @DisplayName("窗口来源：配置页的 retention.days")
    class WindowSource {

        @Test
        @DisplayName("cutoff 严格来自配置页天数（7 天 ⇒ now-7d），且按租户逐个清理")
        void cutoffComesFromConfiguredRetentionDays() {
            RecordingPort db = new RecordingPort();
            RecordingDirectory dir = new RecordingDirectory();

            ChatAuditRetentionService.Report report =
                    service(db, dir, 7, null).runOnce(List.of(TENANT_A, TENANT_B));

            // ⛔ 若窗口偷偷来自 yml（例如 message-days 30），这里会拿到 now-30d
            assertThat(db.lastCutoff)
                    .as("⛔ 清理窗口未使用配置页的 retention.days：页面写 7 天、实际按别的天数删")
                    .isEqualTo(NOW.minus(Duration.ofDays(7)));
            assertThat(db.tenants)
                    .as("⛔ 未逐租户清理（跨租户批量删是本系统最危险的形态）")
                    .containsExactly(TENANT_A, TENANT_B);
            assertThat(db.lastLimit).isEqualTo(500);
            assertThat(dir.lastCutoff).isEqualTo(LocalDate.parse("2026-11-11"));
            assertThat(dir.lastDirectory).isEqualTo("/data/audit");
            assertThat(report.rowsDeleted()).isEqualTo(6);
            assertThat(report.filesDeleted()).isEqualTo(2);
            assertThat(report.retentionDays()).isEqualTo(7);
            assertThat(report.failedCategories()).isZero();
        }

        @Test
        @DisplayName("⛔ 配置读不出来时整轮跳过：绝不按猜出来的天数删除")
        void configFailureSkipsWholeRound() {
            RecordingPort db = new RecordingPort();
            RecordingDirectory dir = new RecordingDirectory();
            ChatAuditRetentionService svc = new ChatAuditRetentionService(
                    db, dir,
                    () -> {
                        throw new IllegalStateException("配置表不可读");
                    },
                    null, "retention-job", 500, Clock.fixed(NOW, ZoneOffset.UTC));

            ChatAuditRetentionService.Report report = svc.runOnce(List.of(TENANT_A));

            assertThat(db.calls.get())
                    .as("⛔ 配置读失败仍执行了删除：按猜出来的窗口删除不可逆")
                    .isZero();
            assertThat(report.failedCategories())
                    .as("⚠️ 整轮跳过必须计入失败类别，否则「没清理」会被读成「没到期」")
                    .isEqualTo(1);
            assertThat(report.hasChanges()).isFalse();
        }

        @Test
        @DisplayName("⛔ retention.days=0 被兜底挡住（否则等于删全部历史）")
        void zeroDaysIsRefused() {
            RecordingPort db = new RecordingPort();
            ChatAuditRetentionService.Report report =
                    service(db, new RecordingDirectory(), 0, null).runOnce(List.of(TENANT_A));

            assertThat(db.calls.get()).isZero();
            assertThat(report.failedCategories()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("失败隔离：两类通道各自独立")
    class FailureIsolation {

        @Test
        @DisplayName("单租户失败不终止整轮，其余租户照常清理")
        void singleTenantFailureDoesNotAbortRound() {
            ChatAuditRetentionPort db = (tenantId, cutoff, limit) -> {
                if (TENANT_A.equals(tenantId)) {
                    throw new IllegalStateException("模拟单租户失败");
                }
                return 4;
            };

            ChatAuditRetentionService.Report report =
                    service(db, new RecordingDirectory(), 30, null)
                            .runOnce(List.of(TENANT_A, TENANT_B));

            assertThat(report.rowsDeleted())
                    .as("⛔ 一个租户的坏数据让其余租户集体不清理 = 集体违约")
                    .isEqualTo(4);
            assertThat(report.failedCategories()).isEqualTo(1);
            assertThat(report.filesDeleted())
                    .as("⛔ DB 类别失败连带文件类别跳过了：库里删了/盘上还在的另一半缺口")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("目录清理失败不影响库内清理，且失败可见")
        void directoryFailureIsVisibleAndDoesNotBlockDatabase() {
            RecordingPort db = new RecordingPort();
            ChatAuditDirectoryRetentionPort dir = (directory, cutoffDate) -> {
                throw new IllegalStateException("目录只读");
            };

            ChatAuditRetentionService.Report report =
                    service(db, dir, 30, null).runOnce(List.of(TENANT_A));

            assertThat(report.rowsDeleted()).isEqualTo(3);
            assertThat(report.failedCategories()).isEqualTo(1);
        }

        @Test
        @DisplayName("⛔ 未接入清理凭据时如实计失败，文件侧仍清理（不伪装成「已履约」）")
        void missingDatabaseCredentialIsReportedNotHidden() {
            RecordingDirectory dir = new RecordingDirectory();

            ChatAuditRetentionService.Report report =
                    service(null, dir, 30, null).runOnce(List.of(TENANT_A));

            assertThat(report.rowsDeleted()).isZero();
            assertThat(report.failedCategories())
                    .as("⚠️ 用应用凭据删不了，静默返回 0 行会被读成「没有到期数据」")
                    .isEqualTo(1);
            assertThat(report.filesDeleted()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("删除凭证")
    class Certificates {

        @Test
        @DisplayName("每个租户一张凭证，且只登记该租户自己的行数（不摊全局数）")
        void certificatePerTenantCarriesOwnRowsOnly() {
            ChatAuditRetentionPort db = (tenantId, cutoff, limit) ->
                    TENANT_A.equals(tenantId) ? 5 : 2;
            CertificateOnlyErasure erasure = new CertificateOnlyErasure();

            service(db, new RecordingDirectory(), 30, erasure)
                    .runOnce(List.of(TENANT_A, TENANT_B));

            assertThat(erasure.certificates).hasSize(2);
            assertThat(erasure.tenants).containsExactly(TENANT_A, TENANT_B);
            assertThat(erasure.certificates.get(0).reason())
                    .isEqualTo(ErasureCertificate.REASON_RETENTION_EXPIRED);
            assertThat(erasure.certificates.get(0).detail())
                    .as("⛔ 凭证登记了别人的行数：举证材料被伪造")
                    .isEqualTo(Map.of("chat_audit_record", 5));
            assertThat(erasure.certificates.get(1).detail())
                    .isEqualTo(Map.of("chat_audit_record", 2));
        }

        @Test
        @DisplayName("本轮无删除则不写空凭证（避免凭证堆积失去信噪比）")
        void noCertificateWhenNothingDeleted() {
            CertificateOnlyErasure erasure = new CertificateOnlyErasure();
            ChatAuditRetentionPort db = (tenantId, cutoff, limit) -> 0;

            ChatAuditRetentionService.Report report =
                    service(db, new RecordingDirectory(), 30, erasure)
                            .runOnce(List.of(TENANT_A));

            assertThat(report.rowsDeleted()).isZero();
            assertThat(erasure.certificates).isEmpty();
        }
    }
}
