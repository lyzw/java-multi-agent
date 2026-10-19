package cn.studykid.multiagent.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.config.RetentionPolicy;

/**
 * 保留期配置 fail-closed 测试（P4-D04 / D-5）。
 *
 * <p><b>本类证明的核心命题</b>：保留期<b>没有安全的默认值</b>。
 * 过短是违规删除（提前销毁审计证据），过长是违规留存（PIPL 超期）。
 * 两个方向都不构成「保守」，因此：
 * <ul>
 *   <li>缺失 → 拒绝；</li>
 *   <li>非整数 → 拒绝；</li>
 *   <li>非正数 → 拒绝；</li>
 *   <li>超出（有意设定的）合理区间 → 拒绝。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么要为「上下限」单独测</b>：
 * 只校验正数是项目里最常见的偷懒写法，而它能放过
 * 「宽限期配成 999 天」这种<b>直接违反 FR-019 验收条款</b>的配置。
 * 「看起来配了」与「配得符合承诺」是两件事。
 */
@DisplayName("P4-D04 保留期配置 fail-closed")
class RetentionPolicyTest {

    @Nested
    @DisplayName("缺失与非整数一律拒绝")
    class MissingOrMalformed {

        @Test
        @DisplayName("F-1 配置项缺失 → 拒绝（禁止退回默认值）")
        void missingRejected() {
            assertThatThrownBy(() -> RetentionPolicy.requireProfileMonths(null))
                    .as("⚠️ 缺失时不得回填默认值——那等于悄悄用了一个没人审过的保留期")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("缺失");
        }

        @Test
        @DisplayName("F-2 非整数 → 拒绝")
        void nonNumericRejected() {
            assertThatThrownBy(() -> RetentionPolicy.requireAuditDays("十二"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不是整数");
        }

        @Test
        @DisplayName("F-3 空串 → 拒绝（不得当作 0 或默认值）")
        void blankRejected() {
            assertThatThrownBy(() -> RetentionPolicy.requireTraceDays("   "))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("取值区间防护")
    class RangeGuard {

        @Test
        @DisplayName("F-4 保留期为 0 天 → 拒绝（等于立即清空全部数据）")
        void zeroRejected() {
            assertThatThrownBy(() -> RetentionPolicy.requirePromptDays("0"))
                    .as("0 天 = 每次清理都清空，属违规操作的另一极")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("允许区间");
        }

        @Test
        @DisplayName("F-5 ⚠️ 撤回宽限期 31 天 → 拒绝（超过 FR-019 的 30 日上限）")
        void graceBeyondLegalLimitRejected() {
            assertThatThrownBy(() -> RetentionPolicy.requireWithdrawalGraceDays("31"))
                    .as("⚠️ FR-019 明文要求「30 日内删除」，31 天就是违约配置，"
                            + "必须在启动期拦住而不是等合规审计")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("F-6 宽限期恰好 30 天 → 允许（边界值不得被误拦）")
        void graceAtLimitAllowed() {
            assertThat(RetentionPolicy.requireWithdrawalGraceDays("30")).isEqualTo(30);
        }

        @Test
        @DisplayName("F-7 ⚠️ 审计保留期 <30 天 → 拒绝（短于一个追溯周期会提前销毁证据）")
        void tooShortAuditRejected() {
            assertThatThrownBy(() -> RetentionPolicy.requireAuditDays("7"))
                    .as("审计是 R-1/R-3 的举证载体，保留 7 天等于让 Sev-0 事件的"
                            + "证据先于调查结论消失")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("F-8 合规取值全部通过（不误伤）")
        void validValuesAccepted() {
            assertThat(RetentionPolicy.requireProfileMonths("12")).isEqualTo(12);
            assertThat(RetentionPolicy.requirePromptDays("90")).isEqualTo(90);
            assertThat(RetentionPolicy.requireAuditDays("180")).isEqualTo(180);
            assertThat(RetentionPolicy.requireTraceDays("30")).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("服务层对非法构造的防护")
    class ServiceGuard {

        @Test
        @DisplayName("F-9 ⚠️ 清理服务的 actor 为空 → 构造即失败（空主体 = R-1 失效）")
        void blankActorRejectedAtConstruction() {
            ErasurePortStub port = new ErasurePortStub();

            assertThatThrownBy(() -> new RetentionPurgeService(
                    port, settings(), "  ", fixedClock()))
                    .as("没有责任主体就不得删除未成年人数据")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("actor");
        }

        @Test
        @DisplayName("F-10 撤回宽限期超限时服务构造失败（不依赖配置层）")
        void graceOutOfRangeRejectedInService() {
            ErasurePortStub port = new ErasurePortStub();

            assertThatThrownBy(() -> new ConsentWithdrawalService(port, 31, fixedClock()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("30");
        }

        @Test
        @DisplayName("F-11 撤回服务的宽限天数与服务配置一致（回执文案不得说谎）")
        void graceDaysExposed() {
            ConsentWithdrawalService service =
                    new ConsentWithdrawalService(new ErasurePortStub(), 30, fixedClock());
            assertThat(service.graceDays())
                    .as("⚠️ 若 API 层硬编码「30 天」而此处取配置，两者迟早不一致——"
                            + "改配置后回执会对监护人说谎")
                    .isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("⚠️ F-12 回归防护：画像清理必须真的执行")
    class ProfileCutoffRegression {

        /**
         * ⚠️ F-12 是这个anti-pattern 的实例，必须钉住：
         * 原写法 {@code Instant.minus(months, ChronoUnit.MONTHS)} 会抛
         * {@code UnsupportedTemporalTypeException}，
         * 而该异常被 {@code runCategory} 转成一条 ERROR 日志后丢弃——
         * 结果是「画像清理这一类别永远为 0 行、永远不生效」，
         * 但任务不报错、其余三类正常、健康检查全绿。
         *
         * <p>修复前的样子：本用例会得到 profilesDeleted=0 / failedCategories=1。
         */
        @Test
        @DisplayName("F-13 ⚠️ 画像清理类别不得因时间单位错误而静默失效")
        void profilePurgeActuallyRuns() {
            ErasurePortStub port = new ErasurePortStub();
            port.profileRows = 1;

            RetentionPurgeService service = new RetentionPurgeService(
                    port, settings(), "retention-job", fixedClock());

            RetentionPurgeService.PurgeReport report = service.runOnce(
                    "33333333-3333-3333-3333-333333333333");

            assertThat(report.failedCategories())
                    .as("⚠️ 失败类别必须为 0：非 0 说明某个清理类别在内部抛异常被吞掉")
                    .isZero();
            assertThat(report.profilesDeleted())
                    .as("⚠️ 画像必须真的被调用清理——"
                            + "若 cutoff 计算抛 UnsupportedTemporalTypeException，"
                            + "这里会是 0 而日志只有一行 ERROR")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("F-14 有变更时必须留下凭证（举证链闭合）")
        void certificateWrittenWhenChanged() {
            ErasurePortStub port = new ErasurePortStub();
            port.profileRows = 2;

            new RetentionPurgeService(port, settings(), "retention-job", fixedClock())
                    .runOnce("33333333-3333-3333-3333-333333333333");

            assertThat(port.certificates)
                    .as("有实际删除时必须写凭证，否则「我们按承诺删了」无法举证")
                    .hasSize(1);
        }
    }

    private static RetentionPurgeService.RetentionSettings settings() {
        return new RetentionPurgeService.RetentionSettings(12, 90, 180, 30, 30, 1000);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
    }

    /** 最小端口桩：本测试不触碰数据库。 */
    private static final class ErasurePortStub
            implements cn.studykid.multiagent.ports.persistence.ErasurePort {

        /** 画像清理返回行数（>0 触发凭证写入）。 */
        int profileRows;

        final java.util.List<cn.studykid.multiagent.ports.persistence.ErasurePort.ErasureCertificate>
                certificates = new java.util.ArrayList<>();

        @Override
        public int purgeExpiredProfiles(String tenantId, Instant cutoff, int limit) {
            return profileRows;
        }

        @Override
        public int blankExpiredPrompts(String tenantId, Instant cutoff, int limit) {
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
            // 无存储
        }

        @Override
        public java.util.List<PendingWithdrawal> findDueWithdrawals(String tenantId, Instant now) {
            return java.util.List.of();
        }

        @Override
        public int eraseWithdrawnStudent(String tenantId, String studentId, String actor) {
            return 0;
        }

        @Override
        public void markWithdrawalErased(String tenantId, String studentId, Instant erasedAt) {
            // 无存储
        }

        @Override
        public void markWithdrawalFailed(String tenantId, String studentId) {
            // 无存储
        }

        @Override
        public void recordCertificate(String tenantId, ErasureCertificate cert) {
            certificates.add(cert);
        }

        @Override
        public java.util.List<ErasureCertificate> findCertificates(
                String tenantId, String reason, int limit) {
            return java.util.List.of();
        }
    }
}
