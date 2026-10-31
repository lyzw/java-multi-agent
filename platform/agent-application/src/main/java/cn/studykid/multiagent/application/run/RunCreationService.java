package cn.studykid.multiagent.application.run;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.idempotency.IdempotencyDecision;
import cn.studykid.multiagent.core.idempotency.IdempotencyKey;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.BudgetPort;
import cn.studykid.multiagent.ports.persistence.CapDualWritePort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunCreationPort;
import cn.studykid.multiagent.ports.persistence.RunLimits;
import org.springframework.stereotype.Service;

/**
 * Run 创建用例（P1-05 / 红线 3）。
 *
 * <p><b>原子提交</b>：Run、初始事件、幂等记录、Outbox 四者同事务写入。
 * 事务边界由 {@link TransactionRunner} 显式控制——实现方<b>不</b>自行提交，
 * 避免「实现内部提交」造成半成品状态。
 *
 * <p><b>幂等语义</b>（ADR-003）：
 * <ul>
 *   <li>首次执行 → 创建 Run；</li>
 *   <li>重复且请求相同 → 返回<b>首次的 Run</b>，不新建；</li>
 *   <li>键相同但请求不同 → 冲突，拒绝。</li>
 * </ul>
 */
@Service
public class RunCreationService {

    private static final Logger log = LoggerFactory.getLogger(RunCreationService.class);

    private final RunCreationPort runCreation;
    private final ExecutionSnapshotPort snapshots;
    private final TransactionRunner tx;
    private final Clock clock;
    /**
     * 预算端口（用于校验 Run 级额度声明与租户额度的<b>创建时一致性</b>）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时「声明了 Run 额度」的创建请求会被
     * <b>拒绝</b>（fail-closed）——
     * 校验缺席若静默放行，等于「Run 级上限可以大于租户总额度」，
     * 那样的上限<b>永远不会被触发</b>，形同虚设。
     */
    private final BudgetPort budget;

    /**
     * 迁移双写端口（{@code execution_capability_snapshot} 的<b>唯一生产写入点</b>）。
     *
     * <p>⚠️ <b>可为空 = 未装配</b>：此时 Run 照常创建，但<b>没有</b>能力快照。
     * 该缺失是<b>可观测</b>的（构造时 WARN），因为快照的六个读方
     * 一律 fail-closed —— 缺快照的后果是「运行期拒跑」，
     * 而不是「悄悄按默认配置跑」。
     *
     * <p>⚠️ <b>与 Run 同事务写（2026-10-28 改，此前在提交之后）</b>：
     * Outbox 消息与 Run 同事务提交，Worker 拿到任务的最晚时间比提交时间只多几十毫秒；
     * 而快照写在事务外要<b>实测晚约 1.15s</b>（闭包解析要串三次库查询），
     * 于是「消息先到、快照后到」，执行侧按 fail-closed 直接把 Run 判死。
     * 闭包越复杂、解析越慢，失败越确定 —— 这个顺序错在根上，不是调参数能绕开的。
     *
     * <p>⚠️ <b>代价（刻意接受）</b>：同一事务里做不到 fail-soft ——
     * PostgreSQL 语句一旦报错，事务进入 aborted 态，即便 Java 侧吞掉异常，
     * commit 仍会失败。因此「双写失败」现在升级为「Run 创建失败」。
     * 取舍理由：能力快照是执行侧<b>唯一的根 Agent 身份来源</b>，
     * 一份「创建成功但永远跑不起来」的 Run 比一次明确的 5xx 更坏。
     */
    private final @Nullable CapDualWritePort dualWrite;

    /**
     * 是否要求每个 Run 必须声明 {@code timeoutMs}（P2-1）。
     *
     * <p>⚠️ <b>默认 {@code false} = 允许「不限时」</b>：这是 {@code CreateRunRequest}
     * 的既定语义，由 {@code CreateRunRequestTimeoutTest} 锁定，
     * 详见带 {@code requireRunTimeout} 参数的构造器说明。
     * 本开关让「超限保护是否强制」成为<b>显式</b>的环境决策，而不是编码巧合。
     */
    private final boolean requireRunTimeout;

    public RunCreationService(@NonNull RunCreationPort runCreation,
                              @NonNull ExecutionSnapshotPort snapshots,
                              @NonNull TransactionRunner tx,
                              @NonNull Clock clock) {
        this(runCreation, snapshots, tx, clock, null, null);
    }

    /**
     * 最完整构造器：含预算端口（可校验 Run 额度与租户额度的一致性）。
     *
     * @param budget 预算端口；{@code null} = 未装配（带额度声明的创建请求将被拒绝）
     */
    public RunCreationService(@NonNull RunCreationPort runCreation,
                              @NonNull ExecutionSnapshotPort snapshots,
                              @NonNull TransactionRunner tx,
                              @NonNull Clock clock,
                              @Nullable BudgetPort budget) {
        this(runCreation, snapshots, tx, clock, budget, null);
    }

    /**
     * 完整构造器：含预算端口与迁移双写端口。
     *
     * @param budget    预算端口；{@code null} = 未装配（带额度声明的创建请求将被拒绝）
     * @param dualWrite 双写端口；{@code null} = 未装配（Run 有能力快照缺失的已知后果）
     */
    public RunCreationService(@NonNull RunCreationPort runCreation,
                              @NonNull ExecutionSnapshotPort snapshots,
                              @NonNull TransactionRunner tx,
                              @NonNull Clock clock,
                              @Nullable BudgetPort budget,
                              @Nullable CapDualWritePort dualWrite) {
        this(runCreation, snapshots, tx, clock, budget, dualWrite, false);
    }

    /**
     * 完整构造器（P2-1）：在 {@link #dualWrite} 之外追加「是否要求 Run 声明超时」的策略开关。
     *
     * <p>⚠️⚠️ <b>为什么默认 {@code false} 而不是默认必填</b>：
     * {@code CreateRunRequest.timeoutMs} 的既有语义是「不传 = 不限时」，
     * 且这条语义被 {@code CreateRunRequestTimeoutTest} 显式锁定
     * （「未声明 timeoutMs → 不限时（不假装有上限）」）。
     * <b>把默认值改成必填，等于在没有产品决策的情况下推翻一条既定契约</b>
     * ——那不是加固，是把「显式声明」偷换成「静默改变行为」。
     * 故：保留既有默认，把「不限时」变成<b>可观测 + 可按环境收紧</b>。
     *
     * <p>⚠️ <b>{@code requireRunTimeout = true} 时的行为</b>（fail-closed）：
     * 未声明 {@code timeoutMs} 的创建请求<b>直接拒绝</b>，绝不留「先创建成功、
     * 再在运行期超时」的中间态 —— Run 已落库再失败，用户侧是「转圈后被判死」，
     * 而创建期拒绝是明确的 4xx。
     *
     * @param budget            预算端口；{@code null} = 未装配
     * @param dualWrite         双写端口；{@code null} = 未装配
     * @param requireRunTimeout 是否要求每个 Run 必须声明 {@code timeoutMs}
     */
    public RunCreationService(@NonNull RunCreationPort runCreation,
                              @NonNull ExecutionSnapshotPort snapshots,
                              @NonNull TransactionRunner tx,
                              @NonNull Clock clock,
                              @Nullable BudgetPort budget,
                              @Nullable CapDualWritePort dualWrite,
                              boolean requireRunTimeout) {
        this.runCreation = runCreation;
        this.snapshots = snapshots;
        this.tx = tx;
        this.clock = clock;
        this.budget = budget;
        this.dualWrite = dualWrite;
        this.requireRunTimeout = requireRunTimeout;
        if (budget == null) {
            log.warn("未装配预算端口：Run 级额度与租户额度的一致性校验不生效，"
                    + "「声明了 Run 额度」的创建请求将被拒绝（fail-closed）");
        }
        if (dualWrite == null) {
            log.warn("未装配迁移双写端口：Run 创建后不会生成 execution_capability_snapshot，"
                    + "依赖该快照的执行准入将一律 fail-closed 拒绝");
        }
        log.info("Run 超时策略: requireRunTimeout={}（false = 允许「不限时」的 Run）",
                requireRunTimeout);
    }

    /**
     * 创建 Run（原子提交）。
     *
     * @param context      认证上下文；<b>tenantId 取自此处，不得取自请求参数</b>
     * @param agentId      Agent ID（来自请求体）；执行快照由本用例<b>在写事务内</b>解析
     * @param key          幂等键
     * @param inputPayload 输入载荷（不含可识别学生信息）
     * @return 创建结果
     * @throws ExecutionSnapshotUnavailableException 该 Agent 未发布或无执行快照（→ 404）
     */
    public @NonNull CreateRunResult createRun(@NonNull TenantContext context,
                                               @NonNull String agentId,
                                               @NonNull IdempotencyKey key,
                                               @NonNull Map<String, Object> inputPayload) {
        return createRun(context, agentId, key, inputPayload, RunLimits.UNLIMITED);
    }

    /**
     * 创建 Run（原子提交），并声明本次 Run 的<b>资源上限</b>。
     *
     * <p>⚠️ <b>资源上限 = 整棵调用树的共同上界</b>（多级 Agent v1.1 · FR-024）：
     * 委派链路的有效预算是 {@code min(根 Run 剩余时间, 本级 timeoutMs)}，
     * 预算同理按 Run 聚合 —— 逐跳只能收窄、不得放宽。
     * 因此根 Run 的截止时间与预算额度必须<b>随 Run 一起落库</b>，
     * 否则「共享上限」在根层就是一句空话。
     *
     * <p>⚠️ <b>子类覆写请覆写本方法</b>（带 {@link RunLimits} 的重载）：
     * 4 参版本只是「不限制」的兼容入口，覆写它<b>不会</b>被调用方命中 ——
     * 调用方一律走本方法。
     *
     * @param context      认证上下文；<b>tenantId 取自此处，不得取自请求参数</b>
     * @param agentId      Agent ID（来自请求体）；执行快照由本用例<b>在写事务内</b>解析
     * @param key          幂等键
     * @param inputPayload 输入载荷（不含可识别学生信息）
     * @param limits       本次 Run 的资源上限（{@link RunLimits#UNLIMITED} = 不限制）
     * @return 创建结果
     * @throws ExecutionSnapshotUnavailableException 该 Agent 未发布或无执行快照（→ 404）
     */
    public @NonNull CreateRunResult createRun(@NonNull TenantContext context,
                                               @NonNull String agentId,
                                               @NonNull IdempotencyKey key,
                                               @NonNull Map<String, Object> inputPayload,
                                               @Nullable Long timeoutMs,
                                               @Nullable BigDecimal budgetAmount) {
        // ⚠️ 截止时刻在**幂等判定之前**算出：它只依赖时钟与预算，
        //    与是否命中既有 Run 无关；命中重放时该值直接丢弃（不写库）。
        //    ⚠️ 换算必须在这里做（服务层持有注入的 Clock）——
        //    调用方自己取 Instant.now() 会引入**第二个时间源**，
        //    二者漂移时截止时刻与实际不符。
        Instant deadlineAt = timeoutMs == null ? null
                : clock.instant().plusMillis(timeoutMs);
        // ── P2-1：把「不限时」从隐式默认变成显式决策 ──────────────────
        //    未声明 timeoutMs → deadlineAt == null → 执行循环「跳过超时检查」，
        //    该 Run 失去任何时间上界（只剩 maxSteps 与租约续期）。
        //    这个后果此前既不体现在日志里、也不体现在配置里，
        //    「没有超时」与「超时设得很长」从外部完全无法区分。
        if (deadlineAt == null) {
            if (requireRunTimeout) {
                // fail-closed：创建期就拒绝，不留「跑起来再被打死」的半成品
                throw new IllegalArgumentException(
                        "本环境要求 Run 必须声明 timeoutMs（实际收到未声明）："
                                + "不限时的 Run 失去 FR-024 整树共享超时上界");
            }
            log.warn("⚠️ 创建「不限时」的 Run: agentId={}（未声明 timeoutMs，"
                    + "该 Run 无业务截止时间，只能靠 maxSteps 与租约兜底）", agentId);
        }
        return createRun(context, agentId, key, inputPayload,
                new RunLimits(deadlineAt, budgetAmount));
    }

    /**
     * 创建 Run（原子提交），并声明本次 Run 的<b>资源上限</b>。
     *
     * <p>⚠️ <b>子类覆写请覆写带资源上限的重载</b>（本方法或 6 参版本）：
     * 4 参版本只是「不限制」的兼容入口，覆写它<b>不会</b>被调用方命中。
     *
     * @param context      认证上下文；<b>tenantId 取自此处，不得取自请求参数</b>
     * @param agentId      Agent ID（来自请求体）；执行快照由本用例<b>在写事务内</b>解析
     * @param key          幂等键
     * @param inputPayload 输入载荷（不含可识别学生信息）
     * @param limits       本次 Run 的资源上限（{@link RunLimits#UNLIMITED} = 不限制）
     * @return 创建结果
     * @throws ExecutionSnapshotUnavailableException 该 Agent 未发布或无执行快照（→ 404）
     */
    public @NonNull CreateRunResult createRun(@NonNull TenantContext context,
                                               @NonNull String agentId,
                                               @NonNull IdempotencyKey key,
                                               @NonNull Map<String, Object> inputPayload,
                                               @NonNull RunLimits limits) {
        RequestDigest digest = RequestDigest.of(inputPayload);
        String correlationId = UUID.randomUUID().toString();

        // 先查幂等记录：命中则不进入写路径，避免无谓的事务与写入
        IdempotencyDecision existing = tx.execute(tenantId(context), () ->
                runCreation.findExistingResource(context.tenantId(), key, digest));

        if (existing.isConflict()) {
            existing.throwIfConflict();
            throw new IllegalStateException("不可达：throwIfConflict 应已抛出");
        }
        if (existing.kind() == IdempotencyDecision.Kind.REPLAY) {
            // 重复请求：直接返回首次结果，<b>不重新执行任何副作用</b>
            return new CreateRunResult(existing.originalResourceId(), true);
        }

        // 首次执行：四者同事务写入
        String runId = UUID.randomUUID().toString();
        OutboxMessage message = new OutboxMessage(
                runId, context.tenantId(), 0, 0L, clock.instant());
        OutboxMessage.assertPayloadSafe(message.toPayload());

        // ⚠️⚠️ 创建时一致性（FR-024 · MA-T17）：Run 级额度必须 ≤ 租户账户总额度。
        //    否则 Run 级上限<b>永远不会被触发</b>（租户级先耗尽），
        //    「这一棵调用树最多花多少」就成了一句没人能兑现的承诺。
        //    ⚠️ 放在写入之前：拒绝一个矛盾的额度声明不该留下半成品 Run。
        //    ⚠️ 幂等重放已在上面返回：重放不产生新的副作用，不该被本次校验拦下。
        assertRunBudgetWithinAccount(context, limits);

        ProvisionedRun provisioned = tx.execute(tenantId(context), () -> {
            // ⚠️⚠️ 快照解析**必须在写事务内**（P4-D18 修复）：
            //   此前由调用方传入的 snapshotId() 恒为随机 UUID，
            //   必然违反 run_snapshot_fk → 端点恒 500。
            //   放事务内解析还顺带消除了「解析完 → Agent 恰好被改版」的窗口：
            //   解析与写入落在同一个快照下。
            //   ⚠️ 不可解析属**客户端错误**（4xx），必须显式抛出而非让
            //   外键以 PSQLException 的形式冒泡成 500。
            ExecutionSnapshotPort.SnapshotRef snapshot = snapshots
                    .resolveSnapshot(context.tenantId(), agentId)
                    .orElseThrow(() -> new ExecutionSnapshotUnavailableException(
                            context.tenantId(), agentId));

            String actualRunId = runCreation.insertRunAtomically(
                    context.tenantId(),
                    runId,
                    snapshot.snapshotId(),
                    key,
                    digest,
                    inputPayload,
                    correlationId,
                    message,
                    limits);
            // ⚠️⚠️ 双写必须在<b>本事务之内</b>（2026-10-28 实测竞态后修正）：
            //   Outbox 消息与本事务一起提交，Worker 最迟在提交后几十毫秒就能领到任务；
            //   而放在事务外的双写<b>实测晚约 1.15s 才落库</b>
            //   （闭包解析要串「指针 → 发布头 → 对象版本」三段查询）。
            //   于是顺序变成「消息先可见、快照后可见」，执行侧读不到根 Agent 身份，
            //   按 fail-closed 把 Run 判成 CONFIG_UNAVAILABLE ——
            //   闭包越复杂、解析越慢，失败越确定。
            //   ⚠️ 副作用：双写SQL报错会连带回滚 Run 创建（PG 事务内做不到 fail-soft），
            //   这是刻意接受的代价，理由见 {@link #dualWrite} 字段注释。
            dualWriteCapabilitySnapshot(context, agentId,
                    new ProvisionedRun(actualRunId, snapshot.agentVersion()));
            return new ProvisionedRun(actualRunId, snapshot.agentVersion());
        });

        return new CreateRunResult(provisioned.runId(), false);
    }

    /**
     * Run 写入事务<b>之内</b>的能力快照双写（G-3 · 快照写入入口）。
     *
     * <p>⚠️ <b>只有首次执行才调用</b>：幂等重放返回的是首次的 Run，
     * 首那次已经把快照写过了；重放再写一遍是重复副作用
     * （虽然仓储用 {@code ON CONFLICT DO NOTHING} 兜住了覆盖，
     * 但「重放不产生副作用」这条不变式不该靠下游的宽容维持）。
     *
     * <p>⚠️ <b>{@code agentVersion} 取写事务内解析出的那一个</b>，
     * 不是「此刻 Agent 的最新版本」：后者与 Run 实际绑定的快照可能已经不同，
     * 那会让快照描述了一份<b>本 Run 并未使用过</b>的配置。
     */
    private void dualWriteCapabilitySnapshot(@NonNull TenantContext context,
                                              @NonNull String agentId,
                                              @NonNull ProvisionedRun provisioned) {
        if (dualWrite == null) {
            return;
        }
        dualWrite.onExecutionSnapshotCreated(context.tenantId(), provisioned.runId(),
                agentId, provisioned.agentVersion(), context.principalId());
    }

    /**
     * 校验 Run 级额度声明与租户账户额度的一致性（创建时）。
     *
     * <p>⚠️ <b>只在声明了 Run 级额度时才校验</b>：
     * 未声明额度 = 不额外限制，与租户额度无冲突，不该给无额度的创建请求
     * 增加「预算系统必须可用」这一新的失败面。
     *
     * <p>⚠️ <b>失败一律 fail-closed</b>：
     * <ul>
     *   <li>未装配预算端口 → 拒绝（校验缺席不能等于校验通过）；</li>
     *   <li>租户未开通预算账户 → 拒绝（没有账户就没有「租户总额度」可比）；</li>
     *   <li>读不到账户 → 拒绝（基础设施故障时不能放行一个无法验证的上限）；</li>
     *   <li>声明额度 &gt; 租户总额度 → 拒绝（该上限永远不会被触发）。</li>
     * </ul>
     *
     * <p>⚠️ <b>已知窗口</b>：本校验读的是<b>独立事务</b>里的账户快照，
     * 与随后的 Run 写入之间、以及与账户后续变更之间存在时间差。
     * 彻底消除需要「在创建事务内读 {@code budget_account}」，
     * 那要求预算端口暴露连接作用域的读接口（本次未做）。
     * 窗口的后果是「额度声明与账户快照短暂不一致」，
     * 真正的硬底线仍是租户级的 DB 原子条件更新（不会超发）。
     */
    private void assertRunBudgetWithinAccount(@NonNull TenantContext context,
                                              @NonNull RunLimits limits) {
        BigDecimal declared = limits.budgetAmount();
        if (declared == null) {
            return;
        }
        if (budget == null) {
            throw new RunBudgetLimitInvalidException(context.tenantId(), declared, null,
                    "未装配预算端口，无法校验 Run 级额度与租户额度的一致性（fail-closed 拒绝）");
        }
        BudgetPort.AccountSnapshot account;
        try {
            account = budget.accountSnapshot(context.tenantId(), BudgetPort.SETTLEMENT_CURRENCY)
                    .orElse(null);
        } catch (RuntimeException e) {
            throw new RunBudgetLimitInvalidException(context.tenantId(), declared, null,
                    "读取租户预算账户失败，无法校验 Run 级额度（fail-closed 拒绝）: "
                            + e.getMessage());
        }
        if (account == null) {
            throw new RunBudgetLimitInvalidException(context.tenantId(), declared, null,
                    "租户未开通预算账户，无法声明 Run 级额度（fail-closed 拒绝）");
        }
        if (declared.compareTo(account.totalLimit()) > 0) {
            throw new RunBudgetLimitInvalidException(context.tenantId(), declared,
                    account.totalLimit(),
                    "声明的 Run 级额度大于租户账户总额度，该上限永远不会被触发: 声明="
                            + declared + ", 租户总额度=" + account.totalLimit());
        }
    }

    /**
     * 事务边界抽象。
     *
     * <p><b>为什么要显式抽象</b>：若在实现里直接写 {@code commit()}，
     * 一旦忘记回滚路径就会留下半成品数据。抽象出来使得「哪些操作在事务内」
     * 一目了然，且便于集成测试替换为真实事务管理器。
     */
    @FunctionalInterface
    public interface TransactionRunner {
        <T> T execute(@NonNull String tenantId, @NonNull TransactionalAction<T> action);

        /** 事务内的动作。 */
        @FunctionalInterface
        interface TransactionalAction<T> {
            T run();
        }
    }

    /**
     * 创建结果。
     *
     * @param runId      Run 标识（新建或复用首次的）
     * @param replayed   是否为重复请求命中既有 Run
     */
    public record CreateRunResult(@NonNull String runId, boolean replayed) {
        public CreateRunResult {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId 不能为空");
            }
        }
    }

    /**
     * 写事务的产出：落库的 Run 标识 + 该 Run 绑定的 Agent 版本号。
     *
     * <p>⚠️ 版本号必须<b>随事务一起带出来</b>，不能在事务外「再查一次当前版本」：
     * 两次读之间 Agent 可以改版，于是能力快照会记录一个
     * <b>本 Run 从未使用过</b>的版本，而快照不可覆盖 —— 失真无法事后修正。
     *
     * @param runId        实际写入的 Run 标识
     * @param agentVersion {@code run.snapshot_id} 所属版本的 {@code version_number}
     */
    private record ProvisionedRun(@NonNull String runId, int agentVersion) {
    }

    /**
     * 取租户 ID 用于事务绑定。
     *
     * <p>注意：这里取的是<b>认证上下文</b>里的 tenantId，
     * 而不是方法参数中的任何客户端输入（P0-D02 NFR-001）。
     */
    private static String tenantId(TenantContext context) {
        return context.tenantId();
    }
}