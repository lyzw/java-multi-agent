package cn.studykid.multiagent.application.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.persistence.ErasurePort;

/**
 * 监护人撤回同意登记（P4-D04 D-3 / FR-019）。
 *
 * <p><b>为什么登记时不直接删除</b>：
 * FR-019 要求「撤回后 30 日内删除」——「30 日内」是**上限**而非目标值。
 * 立即删除当然合规，但会丢掉两件无法事后重建的事：
 * <ol>
 *   <li>撤回动作本身的<b>时间点证据</b>（删完就无从证明监护人何时撤回）；</li>
 *   <li><b>履约期限的可监控性</b>——「已登记 29 天仍未删」必须能被告警发现，
 *       而这要求登记态与删除态是两个可区分的状态。</li>
 * </ol>
 *
 * <p><b>⚠️ 重复登记（同一学生第二次撤回）</b>：
 * 依赖 DB 的 {@code UNIQUE (tenant_id, student_id)} + {@code ON CONFLICT DO NOTHING}，
 * 第二次调用不报错也不产生第二条登记——
 * 否则「撤回两次」会产生两条凭证，被误读为「删了两次」。
 */
public class ConsentWithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(ConsentWithdrawalService.class);

    /**
     * 学生标识格式。
     *
     * <p>⚠️ 与 V8 {@code consent_student_id_format_chk}、V2
     * {@code student_profile_id_format_chk} 保持<b>完全一致</b>。
     * 应用层校验的目的是给出可读错误，<b>不是</b>替代 DB 约束——
     * 后者才是最后防线（直连 SQL / 迁移脚本都绕不过）。
     */
    private static final Pattern STUDENT_ID = Pattern.compile("^stu_[a-z0-9]{16}$");

    private final ErasurePort erasurePort;
    private final int graceDays;
    private final Clock clock;

    public ConsentWithdrawalService(@NonNull ErasurePort erasurePort,
                                    int graceDays,
                                    @NonNull Clock clock) {
        if (graceDays < 0 || graceDays > 30) {
            throw new IllegalArgumentException(
                    "撤回宽限期必须落在 [0, 30] 天（FR-019 要求 30 日内删除）：" + graceDays);
        }
        this.erasurePort = erasurePort;
        this.graceDays = graceDays;
        this.clock = clock;
    }

    /**
     * 登记一次撤回同意。
     *
     * @return 删除截止时点，供调用方回执给监护人
     */
    public Instant withdraw(@NonNull String tenantId, @NonNull String studentId) {
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("租户标识不能为空");
        }
        if (!STUDENT_ID.matcher(studentId).matches()) {
            // ⚠️ 提前拒绝而非交给 DB：约束名对调用方毫无意义，
            //    而「格式应为 stu_ + 16 位」是可操作的说明。
            throw new IllegalArgumentException(
                    "学生标识格式不合法（应为 stu_ 前缀 + 16 位小写十六进制）：" + studentId);
        }
        Instant now = clock.instant();
        Instant dueAt = now.plus(graceDays, ChronoUnit.DAYS);
        erasurePort.registerWithdrawal(tenantId, studentId, now, dueAt);
        log.info("已登记监护人撤回同意: tenant={}, dueAt={}（宽限 {} 天）",
                tenantId, dueAt, graceDays);
        return dueAt;
    }

    /**
     * 供 API 层展示的宽限天数。
     *
     * <p>⚠️ 单独暴露这个方法是为了让回执文案与配置保持一致——
     * 若 API 层硬编码「30 天」，改配置后回执会说谎。
     */
    public int graceDays() {
        return graceDays;
    }
}
