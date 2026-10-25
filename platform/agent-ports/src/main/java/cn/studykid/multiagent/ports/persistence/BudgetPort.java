package cn.studykid.multiagent.ports.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.run.ToolOperationState;

/**
 * 预算预留与结算端口（P3-D04 / FR-012 / R-007）。
 *
 * <p><b>为什么预算必须进持久层而不是内存计数</b>：
 * 「并发预留不超发」的唯一可靠保证是 <b>DB 原子条件更新</b> ——
 * {@code UPDATE ... WHERE reserved + settled + ? <= total_limit}。
 * 内存计数在多实例部署下必然失守，且「检查」与「扣减」之间存在
 * check-then-act 窗口（N 个并发可同时读到余额充足）。
 * {@code budget_account.budget_no_overspend} CHECK 是最后防线，
 * 但仅有它会出现「随机预算不足」，因此两者缺一不可。
 *
 * <p><b>失败原因必须用枚举而非 boolean</b>：{@code UPDATE} 在
 * 「账户不存在」与「额度不足」两种情况下都返回 0 行。若把两者
 * 都报成 false，运维看到「预算不足」会去加额度，而真实原因是
 * <b>该租户根本没有开通预算账户</b> —— 排查方向完全错误。
 *
 * <p><b>UNKNOWN 不自动释放</b>（红线，见 {@link ToolOperationState#UNKNOWN}）：
 * 请求已发出但响应丢失时成本可能已发生，释放预留等于白送额度。
 * 只有确定失败（FAILED）与对账收敛（RECONCILED_*）才可离开 RESERVED。
 */
public interface BudgetPort {

    /**
     * 本平台的<b>结算币种</b>（预留与结算都记在这个币种的账户上）。
     *
     * <p>⚠️ <b>为什么必须显式存在</b>：预留行本身<b>不带币种</b>
     * （币种在 {@code budget_account} 上），因此「某 Run 的额度」
     * 与「某租户的额度」必须落在<b>同一个币种</b>上才有可比性。
     * 跨币种折算属 P4（L-6），当前只结算人民币。
     */
    String SETTLEMENT_CURRENCY = "CNY";

    /**
     * 预留额度（原子）。
     *
     * <p>⚠️ 实现必须用<b>单条条件 UPDATE</b>，禁止「先 SELECT 检查再 UPDATE」——
     * 后者的检查与扣减之间存在窗口，并发下可超发。
     *
     * <p>⚠️ <b>两级上限都必须在这次预留里原子生效</b>：
     * <ol>
     *   <li><b>租户级</b>（{@code budget_account.total_limit}）：
     *       单条条件 UPDATE 天然原子；</li>
     *   <li><b>Run 级</b>（{@code run.budget_amount}）：该上限的判定口径是
     *       <b>多行聚合</b>（{@code budget_reservation} 按 {@code run_id} 汇总），
     *       多行聚合<b>没有单条条件 UPDATE 可用</b> ——
     *       实现必须用<b>行锁把同一 Run 的预留串行化</b>
     *       （锁 {@code run} 行），再在同一事务内判定与扣减。
     *       读聚合再扣减（check-then-act）会让并发预留同时通过判定 → 突破 Run 上限。</li>
     * </ol>
     * 实现若只做租户级，Run 级上限就<b>形同虚设</b>：
     * 「这一棵调用树最多花多少」会退化成「只要租户还有钱就能一直花」。
     *
     * @param operationId 关联的 ToolOperation；<b>为 null 时不做唯一性约束</b>
     *                    （部分唯一索引仅对非空 operation_id 生效）
     * @return 预留结果；失败时 {@link ReserveOutcome} 给出<b>可区分</b>的原因
     */
    @NonNull ReserveOutcome reserve(@NonNull String tenantId,
                                    @NonNull String runId,
                                    String operationId,
                                    @NonNull BigDecimal amount);

    /**
     * 结算预留（RESERVED → SETTLED）。
     *
     * <p>⚠️ <b>重复结算不得重复计费</b>（FR-012 / AT-BUDGET-002）：
     * <ul>
     *   <li>计量写入用 {@code INSERT ... ON CONFLICT DO NOTHING}，
     *       不可用「先查后插」（check-then-act）；</li>
     *   <li>计量插入与账户金额更新<b>必须同事务</b>，
     *       任一失败整体回滚 —— 半边落地会造成永久账实不符；</li>
     *   <li>命中冲突（重复结算）时<b>不得</b>再次累加 {@code settled_amount}。</li>
     * </ul>
     *
     * @param actualAmount 实际结算金额（须在 {@code [0, reserved]} 内）
     * @param modelId      模型标识（写入 usage_record；可空表示非模型计费）
     * @return 结算结果；{@code false} 表示预留不存在或已非 RESERVED
     */
    boolean settle(@NonNull String tenantId,
                   @NonNull String reservationId,
                   @NonNull BigDecimal actualAmount,
                   String modelId);

    /**
     * 释放预留（RESERVED → RELEASED），额度退回账户。
     *
     * <p>⚠️ <b>重复释放不得二次退回</b>：返回 false 且不改账户，
     * 否则额度会被凭空放大（比超发更隐蔽 —— 它让后续所有预留都可通过）。
     *
     * <p>⚠️ <b>调用方责任</b>：UNKNOWN 状态下<b>禁止</b>调用本方法。
     * 这是 R-007 的核心约束，端口不代为判断（状态机在
     * {@link ToolOperationState}，调用方持有该上下文）。
     *
     * @return 释放结果；{@code false} 表示预留不存在或已非 RESERVED
     */
    boolean release(@NonNull String tenantId,
                    @NonNull String reservationId,
                    @NonNull String reason);

    /** @return 该逻辑操作的预留（对账与幂等入口）。 */
    @NonNull Optional<Reservation> findByOperation(@NonNull String tenantId,
                                                   @NonNull String operationId);

    /**
     * 扫描悬挂预留：仍为 RESERVED，但关联的 ToolOperation 已终态。
     *
     * <p>用于对账。<b>UNKNOWN 的预留不在此列</b>——它不是悬挂，
     * 是「等对账」，处置路径不同（见 {@link OrphanedReservation}）。
     */
    @NonNull List<OrphanedReservation> findOrphaned(@NonNull String tenantId);

    /** @return 账户余额快照（对账比对用）。 */
    @NonNull Optional<AccountSnapshot> accountSnapshot(@NonNull String tenantId,
                                                       @NonNull String currency);

    /**
     * 账实一致性核对：{@code SUM(预留明细)} 是否等于 {@code account.reserved_amount}。
     *
     * @return 一致返回 true；不一致返回 false（<b>调用方只告警，不得自动改账户</b>）
     */
    boolean isConsistent(@NonNull String tenantId, @NonNull String currency);

    /** 预留结果。 */
    enum ReserveOutcome {

        /** 预留成功，新建预留行。 */
        RESERVED(true),

        /** 幂等重放：同 operation 已有预留，返回既有预留（未重复扣减）。 */
        REPLAYED(true),

        /** 账户存在但额度不足。 */
        INSUFFICIENT(false),

        /**
         * ⚠️ <b>该 Run 的 Run 级额度不足</b>：本次预留会突破 {@code run.budget_amount}。
         *
         * <p>⚠️ <b>与 {@link #INSUFFICIENT} 必须可区分</b>：
         * {@code INSUFFICIENT} 是「<b>租户账户</b>余额不足」——
         * 处置方向是「给租户加额度」；
         * 本值是「<b>这一棵调用树</b>自己的上限不够」——
         * 处置方向是「调小该 Run 的额度声明 / 减少本次开销」。
         * 两者混报，运维会往错误方向排查（给租户加钱后问题依旧）。
         */
        RUN_BUDGET_EXHAUSTED(false),

        /** ⚠️ 该租户未开通预算账户 —— fail-closed 拒绝，<b>不是</b>额度不足。 */
        NO_ACCOUNT(false),

        /** 金额非法（≤ 0 或超出该逻辑操作的既有预留额）→ fail-closed。 */
        FAIL_CLOSED(false);

        private final boolean granted;

        ReserveOutcome(boolean granted) {
            this.granted = granted;
        }

        /** @return 是否已获得额度（可用于放行工具执行）。 */
        public boolean granted() {
            return granted;
        }
    }

    /** 预留记录。 */
    record Reservation(String reservationId,
                       String runId,
                       String operationId,
                       BigDecimal reservedAmount,
                       BigDecimal settledAmount,
                       String state,
                       String currency) {
    }

    /**
     * 悬挂预留。
     *
     * @param operationState 关联 ToolOperation 的终态（对账据此决定结算或释放）
     */
    record OrphanedReservation(String reservationId,
                               String operationId,
                               BigDecimal reservedAmount,
                               String operationState,
                               java.time.Instant createdAt) {
    }

    /** 账户余额快照。 */
    record AccountSnapshot(String accountId,
                           String currency,
                           BigDecimal totalLimit,
                           BigDecimal reservedAmount,
                           BigDecimal settledAmount) {

        /** @return 剩余可预留额度。 */
        @NonNull
        public BigDecimal available() {
            return totalLimit.subtract(reservedAmount).subtract(settledAmount);
        }
    }

    /** 预留状态常量（与 DB {@code reservation_state_valid} CHECK 一致）。 */
    final class States {

        private States() {
        }

        /** 已预留，仍占用额度。 */
        public static final String RESERVED = "RESERVED";

        /** 已结算，额度转入 settled。 */
        public static final String SETTLED = "SETTLED";

        /** 已释放，额度退回。 */
        public static final String RELEASED = "RELEASED";
    }
}
