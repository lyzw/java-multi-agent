package cn.studykid.multiagent.application.cap;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapApproval;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapApprovalPort;

/**
 * 审批状态机（W-PERM-P3-T3 · §5.3.3 审批状态机 · §5.3.1 审批绑定契约）。
 *
 * <p>把「定位候选 → 校验绑定 → 原子预占 → 状态迁移」串成<b>一条</b>链，
 * 并把结论映射到 §9.1 S6a~e 的五个具体原因码。
 *
 * <p>⚠️ <b>五个码必须彼此可区分</b>（§9.1 冻结门槛「含 S6 具体码」）：
 * <ul>
 *   <li>{@code APPROVAL_NOT_FOUND} —— 压根没有可用审批（无候选 / 非本主体 / 已取消）；</li>
 *   <li>{@code APPROVAL_EXPIRED} —— 有审批但已过有效期；</li>
 *   <li>{@code APPROVAL_SCOPE_MISMATCH} —— 有审批但绑定不符
 *       （范围被突破、参数被替换、闭包指纹或授权版本已变）；</li>
 *   <li>{@code APPROVAL_RESERVED_BY_OTHER} —— 审批已被别的调用预占；</li>
 *   <li>{@code APPROVAL_REPLAY_REJECTED} —— 审批已被消费（重放）。</li>
 * </ul>
 * 若把这五种情形都归成「审批不通过」，则「审批被谁占用」「参数是否被替换」
 * 这两条最重要的安全结论就无法从日志与决策中读出 —— 验收矩阵也就形同虚设。
 *
 * <p>⚠️ <b>逐候选校验</b>：同一工具可能有多份不同范围的审批，
 * 故按创建时间升序逐个校验，取第一条可用者。
 * 全部候选都不可用时，按「绑定不符 → 已过期 → 不存在」的顺序回落原因码。
 */
public class ApprovalStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ApprovalStateMachine.class);

    private final CapApprovalPort approvals;
    private final ApprovalBindingService binding;

    public ApprovalStateMachine(@NonNull CapApprovalPort approvals,
                                 @NonNull ApprovalBindingService binding) {
        this.approvals = approvals;
        this.binding = binding;
    }

    /**
     * 预占请求（步骤 11 的输入）。
     *
     * @param tenantId           租户
     * @param principalId        本次调用主体（§5.3.1 绑定项）
     * @param toolId             目标工具
     * @param runId              关联 Run
     * @param invocationId       调用标识（= 账本 operationId）
     * @param operationId        账本逻辑操作标识
     * @param arguments          实际参数
     * @param closureFingerprint 本 Run 的闭包指纹
     * @param authzVersion       当前主体授权版本
     * @param now                当前时刻（注入以便测试确定过期判定）
     */
    public record AcquireRequest(
            @NonNull String tenantId,
            @NonNull String principalId,
            @NonNull String toolId,
            @Nullable String runId,
            @NonNull String invocationId,
            @NonNull String operationId,
            @NonNull Map<String, Object> arguments,
            @NonNull String closureFingerprint,
            long authzVersion,
            @NonNull Instant now) {
    }

    /**
     * 预占结论。
     *
     * @param approvalId 预占成功的审批 id；未成功时为 {@code null}
     * @param reasonCode 原因码（成功时为 {@link ReasonCode#SUCCESS}）
     * @param acquired   是否预占成功
     * @param detail     诊断信息（哪个绑定项不符等）
     */
    public record Acquisition(@Nullable String approvalId,
                               @NonNull ReasonCode reasonCode,
                               boolean acquired,
                               @Nullable String detail,
                               /**
                                * ⚠️ M3 / G1：是否处于「待人工决策」状态。
                                *
                                * <p>⚠️ 与 {@code acquired=false} <b>并存</b>：
                                * 「未获得授权」有两种截然不同的处置 ——
                                * 「永远过不了」（绑定不符/过期）应当<b>失败</b>，
                                * 「等一等就能过」（待人工决策）应当<b>挂起</b>。
                                * 只看 {@code acquired} 无法区分二者。
                                */
                                boolean requiresHumanDecision) {

        static @NonNull Acquisition ok(@NonNull String approvalId) {
            return new Acquisition(approvalId, ReasonCode.SUCCESS, true, null, false);
        }

        static @NonNull Acquisition deny(@NonNull ReasonCode reasonCode, @Nullable String detail) {
            return new Acquisition(null, reasonCode, false, detail, false);
        }

        /**
         * 待人工决策（M3 / G1）：未获得授权，但<b>可挂起等待人批</b>。
         *
         * @param detail 诊断信息（指向哪条待办）
         */
        static @NonNull Acquisition awaitingHuman(@Nullable String detail) {
            return new Acquisition(null, ReasonCode.APPROVAL_NOT_FOUND, false, detail, true);
        }
    }

    /**
     * 预占审批（步骤 11）。
     *
     * @param request 预占请求
     * @return 预占结论
     */
    public @NonNull Acquisition acquire(@NonNull AcquireRequest request) {
        List<CapApproval> candidates = approvals.findByTool(request.tenantId(), request.toolId());
        if (candidates.isEmpty()) {
            return Acquisition.deny(ReasonCode.APPROVAL_NOT_FOUND,
                    "工具无任何审批记录: tool=" + request.toolId());
        }

        boolean mismatchSeen = false;
        String mismatchDetail = null;
        boolean expiredSeen = false;
        // ⚠️ M3 / G1：存在「待人工决策」的审批（尚未有人批准）。
        //    与「绑定不符」「已过期」是**三种不同语义**，不可合并：
        //    前者等一等就能过，后两者这单永远过不了。
        boolean awaitingHumanSeen = false;
        String awaitingHumanDetail = null;

        for (CapApproval candidate : candidates) {
            // ⚠️ 非本主体的审批直接跳过（**不算绑定不符**）：
            //    「有没有我的审批」与「审批是否被篡改」是两件事，
            //    混为一谈会让「审批不存在」被误报成「参数被替换」。
            if (!candidate.principalId().equals(request.principalId())) {
                continue;
            }

            // ── 1. 绑定校验（先）────────────────────────────────────
            String bindingFailure = verifyBinding(candidate, request);
            if (bindingFailure != null) {
                mismatchSeen = true;
                if (mismatchDetail == null) {
                    mismatchDetail = bindingFailure;
                }
                continue;
            }

            // ── 2. 有效期校验 ───────────────────────────────────────
            if (!candidate.expiresAt().isAfter(request.now())) {
                expiredSeen = true;
                continue;
            }

            // ── 3. 状态机（§5.3.3）─────────────────────────────────
            switch (candidate.state()) {
                case PENDING -> {
                    return reserve(candidate, request);
                }
                case RESERVED -> {
                    if (candidate.invocationId() != null
                            && candidate.invocationId().equals(request.invocationId())) {
                        // ⚠️ 同一调用重复预占 = 幂等命中（崩溃后重放）
                        return Acquisition.ok(candidate.approvalId());
                    }
                    return Acquisition.deny(ReasonCode.APPROVAL_RESERVED_BY_OTHER,
                            "审批已被其他调用预占: approval=" + candidate.approvalId());
                }
                case INTENT_SUBMITTED -> {
                    // ⚠️ 同一调用已提交执行意图 = 已越过预占阶段（幂等）
                    if (candidate.invocationId() != null
                            && candidate.invocationId().equals(request.invocationId())) {
                        return Acquisition.ok(candidate.approvalId());
                    }
                    return Acquisition.deny(ReasonCode.APPROVAL_RESERVED_BY_OTHER,
                            "审批已被其他调用预占并提交意图: approval=" + candidate.approvalId());
                }
                case CONSUMED -> {
                    return Acquisition.deny(ReasonCode.APPROVAL_REPLAY_REJECTED,
                            "审批已被消费，拒绝重放: approval=" + candidate.approvalId());
                }
                case EXPIRED -> {
                    expiredSeen = true;
                }
                case CANCELLED -> {
                    // ⚠️ 已取消 = 对本次调用不可见（等价于不存在）
                    log.debug("跳过已取消审批: approval={}", candidate.approvalId());
                }
                case AWAITING_HUMAN -> {
                    // ⚠️⚠️ 待人工决策（尚未有人批准）：**不可被预占**。
                    //    既不能当「可用授权」（否则 = 人还没批就执行 = Sev-0），
                    //    也不能当「绑定不符 / 已过期」（语义不同：那两种是「这单永远过不了」，
                    //    而本状态是「等一等就能过」）。
                    //    故单独标记，调用方据此把 Run 挂起等待人批。
                    awaitingHumanSeen = true;
                    if (awaitingHumanDetail == null) {
                        awaitingHumanDetail = "存在待人工决策的审批: approval="
                                + candidate.approvalId();
                    }
                }
            }
        }

        if (mismatchSeen) {
            return Acquisition.deny(ReasonCode.APPROVAL_SCOPE_MISMATCH, mismatchDetail);
        }
        if (expiredSeen) {
            return Acquisition.deny(ReasonCode.APPROVAL_EXPIRED,
                    "审批已过有效期: tool=" + request.toolId());
        }
        if (awaitingHumanSeen) {
            // ⚠️ 「待人工决策」不是失败，是**暂停**：调用方据此登记/沿用待办并挂起 Run。
            return Acquisition.awaitingHuman(awaitingHumanDetail);
        }
        return Acquisition.deny(ReasonCode.APPROVAL_NOT_FOUND,
                "无可用审批: tool=" + request.toolId());
    }

    /**
     * 登记待人工决策的审批（M3 / G1 · 运行时 HITL）。
     *
     * <p>⚠️ <b>何时用</b>：运行期发现<b>没有可用的预授权</b>（{@link #acquire} 结论为
     * {@code APPROVAL_NOT_FOUND}）且该工具的风险等级要求审批时，
     * 由调用方登记一条待办并把 Run 挂起（PARK），待人批准后恢复。
     *
     * <p>⚠️ <b>范围 = 精确匹配本次参数</b>：这里登记的范围是
     * 「本次调用这一组参数」，不是「一类参数的允许区间」——
     * 因为运行期只知道<b>实际</b>参数，无法凭空定义允许区间
     * （凭空放宽 = 给自己开闸；凭空收紧 = 人批了也不生效）。
     * 人批准的就是这一次调用，这正是 HITL 的语义。
     *
     * <p>⚠️ <b>不可被预占</b>：登记的状态是 {@code AWAITING_HUMAN}，
     * {@link #acquire} 只会跳过它——必须先由人批准（转为 PENDING）才允许预占。
     *
     * @param request       登记请求（复用预占请求的全部绑定要素）
     * @param approverScope 允许审批的角色范围
     * @param ttl           有效期
     * @return 新建的审批 id（登记失败即抛异常，绝不返回「假成功」）
     */
    public @NonNull String requestHuman(@NonNull AcquireRequest request,
                                        @NonNull String approverScope,
                                        @NonNull Duration ttl) {
        // ⚠️ 精确匹配本次参数：每个参数一条 eq 约束。
        Map<String, ApprovalScope.Constraint> constraints = new java.util.LinkedHashMap<>();
        request.arguments().forEach((name, value) ->
                constraints.put(name, new ApprovalScope.Constraint("eq", value)));
        ApprovalScope scope = ApprovalScope.of(request.toolId(), constraints);
        // ⚠️ 绑定要素必须由同一个 binding 服务生成（范围哈希 + 有效参数摘要），
        //    否则消费侧重验时范围哈希与参数摘要对不上 → 审批永远匹配不上。
        ApprovalBindingService.Binding generated = binding.generate(scope, request.arguments());

        String approvalId = approvals.requestHumanApproval(new CapApprovalPort.CapApprovalRequest(
                request.tenantId(), request.principalId(), request.toolId(),
                request.toolId(), generated.approvalScopeHash(), scope.canonicalText(),
                generated.effectiveArgsHash(), request.closureFingerprint(),
                request.authzVersion(), approverScope, request.now().plus(ttl)));
        log.info("已登记待人工审批: approval={}, tool={}, run={}, expiresAt={}",
                approvalId, request.toolId(), request.runId(), request.now().plus(ttl));
        return approvalId;
    }

    /**
     * 绑定校验（§5.3.1）。
     *
     * @return 不符项描述；符合时返回 {@code null}
     */
    @Nullable
    private String verifyBinding(@NonNull CapApproval candidate,
                                 @NonNull AcquireRequest request) {
        // ── 范围定义完整性：定义全文的摘要必须等于落库的哈希 ──
        ApprovalScope scope;
        try {
            scope = ApprovalScope.parse(candidate.scopeDefinition());
        } catch (RuntimeException e) {
            // ⚠️ 定义损坏 → 视为绑定不符（fail-closed），
            //    绝不「解析失败就跳过约束」—— 那等于取消范围校验。
            return "审批范围定义无法解析: approval=" + candidate.approvalId();
        }
        if (!scope.hash().equals(candidate.approvalScopeHash())) {
            return "审批范围定义与范围哈希不一致: approval=" + candidate.approvalId();
        }
        if (!scope.toolId().equals(candidate.toolId())) {
            return "审批范围的目标工具与审批记录不一致: approval=" + candidate.approvalId();
        }
        if (!candidate.closureFingerprint().equals(request.closureFingerprint())) {
            // ⚠️ 闭包指纹变了 = 本 Run 依赖的能力集已变 → 审批不再适用
            return "闭包指纹与审批绑定不一致: approval=" + candidate.approvalId();
        }
        if (candidate.authzVersion() != request.authzVersion()) {
            // ⚠️ 授权版本已变（撤销/收紧）→ 审批失效（§5.5）
            return "授权版本与审批绑定不一致: approval=" + candidate.approvalId();
        }
        ApprovalBindingService.Verdict verdict = binding.verify(scope,
                new ApprovalBindingService.Binding(candidate.approvalScopeHash(),
                        candidate.effectiveArgsHash()),
                request.arguments());
        if (!verdict.matched()) {
            return "参数与审批绑定不一致（违反=" + verdict.violated() + "）: approval="
                    + candidate.approvalId();
        }
        return null;
    }

    /**
     * 原子预占（§5.3.3 RESERVED）。
     *
     * <p>⚠️ 预占必须<b>独占</b>：并发预占同一审批时只能有一个成功。
     * 独占性由仓储层的 CAS 更新保证（期望原状态 = PENDING）。
     */
    private @NonNull Acquisition reserve(@NonNull CapApproval candidate,
                                         @NonNull AcquireRequest request) {
        CapApprovalPort.ReservationOutcome outcome = approvals.reserve(
                request.tenantId(), candidate.approvalId(), request.invocationId(),
                request.operationId(), request.runId(), candidate.effectiveArgsHash(),
                request.closureFingerprint(), request.authzVersion());
        return switch (outcome) {
            case RESERVED, ALREADY_RESERVED_BY_SAME -> Acquisition.ok(candidate.approvalId());
            case ALREADY_RESERVED_BY_OTHER ->
                    Acquisition.deny(ReasonCode.APPROVAL_RESERVED_BY_OTHER,
                            "审批已被其他调用预占: approval=" + candidate.approvalId());
            case ALREADY_CONSUMED ->
                    Acquisition.deny(ReasonCode.APPROVAL_REPLAY_REJECTED,
                            "审批已被消费，拒绝重放: approval=" + candidate.approvalId());
            case EXPIRED ->
                    Acquisition.deny(ReasonCode.APPROVAL_EXPIRED,
                            "审批已过有效期: approval=" + candidate.approvalId());
            case CANCELLED ->
                    Acquisition.deny(ReasonCode.APPROVAL_NOT_FOUND,
                            "审批已取消: approval=" + candidate.approvalId());
            case NOT_FOUND, INTENT_SUBMITTED ->
                    Acquisition.deny(ReasonCode.APPROVAL_NOT_FOUND,
                            "预占失败: outcome=" + outcome);
            // ⚠️ 待人工决策：不可被预占（人还没批）→ 挂起而非失败。
            case AWAITING_HUMAN_DECISION -> Acquisition.awaitingHuman(
                    "审批尚未有人批准: outcome=" + outcome);
        };
    }

    /**
     * 标记「执行意图已提交」（步骤 13b · {@code RESERVED → INTENT_SUBMITTED}）。
     *
     * @return 是否迁移成功（已迁移过视为幂等成功）
     */
    public boolean markIntentSubmitted(@NonNull String tenantId,
                                       @NonNull String approvalId,
                                       @NonNull String invocationId) {
        return approvals.markIntentSubmitted(tenantId, approvalId, invocationId);
    }

    /**
     * 确认消费（步骤 15 · {@code INTENT_SUBMITTED → CONSUMED}）。
     *
     * @param terminalStatus 关联终态（SUCCEEDED / FAILED / UNKNOWN）
     * @return 是否迁移成功
     */
    public boolean consume(@NonNull String tenantId,
                           @NonNull String approvalId,
                           @NonNull String invocationId,
                           @NonNull String terminalStatus) {
        return approvals.consume(tenantId, approvalId, invocationId, terminalStatus);
    }

    /**
     * 取消审批（补偿 · §5.3.3 旁路 CANCELLED）。
     *
     * <p>⚠️ <b>幂等</b>：重复取消不产生二次效果，也不报错。
     *
     * @return 是否发生状态迁移（已取消返回 {@code false}）
     */
    public boolean cancel(@NonNull String tenantId,
                          @NonNull String approvalId,
                          @NonNull String invocationId,
                          @NonNull String reason) {
        return approvals.cancel(tenantId, approvalId, invocationId, reason);
    }
}
