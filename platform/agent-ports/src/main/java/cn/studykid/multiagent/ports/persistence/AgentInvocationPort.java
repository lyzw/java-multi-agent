package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.AgentInvocation;
import cn.studykid.multiagent.core.orchestration.AgentInvocationStatus;

/**
 * 委派调用记录仓储端口（多级 Agent v1.1 · FR-022 / P2-MA-01）。
 *
 * <p>⚠️ <b>写入语义是「插入幂等的逻辑调用」</b>：同一
 * {@code (tenantId, invocationId)} 重复投递时<b>不得</b>创建第二条逻辑调用
 * （MA-T10）。这由端口的幂等契约与 DB 主键共同保证。
 *
 * <p>⚠️ <b>终态写入必须带 fencing token</b>：Worker 崩溃后由新世代接管，
 * 旧世代带着过期 token 提交结果必须<b>失败</b>（MA-T12）。
 * 端口用「返回布尔值」表达 CAS 结果，而不是抛异常 ——
 * 「token 过期」是<b>正常的并发结果</b>，不是系统故障。
 */
public interface AgentInvocationPort {

    /**
     * 插入一条委派调用记录（<b>幂等</b>）。
     *
     * @param invocation 调用记录
     * @return {@code true} 表示本次插入；{@code false} 表示已存在（同 invocationId）
     */
    boolean insert(@NonNull AgentInvocation invocation);

    /**
     * 按调用标识读取。
     *
     * @param tenantId    租户标识
     * @param invocationId 调用标识
     * @return 调用记录；不存在时为 {@link Optional#empty()}
     */
    @NonNull Optional<AgentInvocation> find(@NonNull String tenantId,
                                             @NonNull String invocationId);

    /**
     * 列出某次 Run 的全部委派调用（<b>按开始时间升序</b>，便于重建调用树）。
     *
     * @param tenantId 租户标识
     * @param runId    根 Run 标识
     * @return 调用记录列表；无委派时为空列表
     */
    @NonNull List<AgentInvocation> listByRun(@NonNull String tenantId,
                                             @NonNull String runId);

    /**
     * 写入终态（CAS）。
     *
     * <p>⚠️ <b>只允许前进到终态</b>：非终态 → 终态；已是终态则<b>不覆盖</b>
     * （第一次写入的终态是权威事实，重复投递不得改写结果）。
     *
     * <p>⚠️ <b>fencing token 不匹配即拒绝</b>：旧世代不得提交结果（MA-T12）。
     *
     * @param tenantId     租户标识
     * @param invocationId 调用标识
     * @param status       终态
     * @param reasonCode   原因码（可空）
     * @param outputDigest 输出摘要（可空）
     * @param finishedAt   结束时间
     * @param fencingToken 世代令牌
     * @return 写入结果（三态可区分，见 {@link TerminalWriteOutcome}）
     */
    @NonNull TerminalWriteOutcome markTerminal(@NonNull String tenantId,
                                                @NonNull String invocationId,
                                                @NonNull AgentInvocationStatus status,
                                                @Nullable String reasonCode,
                                                @Nullable String outputDigest,
                                                @Nullable Instant finishedAt,
                                                long fencingToken);

    /**
     * <b>接管</b>该 Run 的在途委派记录（MA-T12 · P3-MA-02）。
     *
     * <p>⚠️ <b>为什么需要这一步</b>：Worker 崩溃后由新世代接管，
     * 但 {@code agent_invocation} 里的在途记录仍带着<b>旧世代</b>的 token ——
     * 于是新世代<b>写不了</b>终态（CAS 被 fencing 拦下 → 报 UNKNOWN），
     * 旧世代<b>也</b>写不了（它已被接管）。结果是那条记录<b>永远悬在 RUNNING</b>，
     * 无人能收敛它，重放时只能拿到「既有 RUNNING 状态」→ 父 Agent 收到
     * 「执行失败」而真实原因（Worker 崩溃）被掩盖。
     *
     * <p>⚠️ <b>提升 token ≠ 放弃 fencing</b>：提升后<b>新世代</b>才能写终态，
     * <b>旧世代</b>带着更小的 token 依然会被 CAS 拒绝（MA-T12 不变）。
     *
     * <p>⚠️ <b>只动在途记录</b>：已是终态的记录<b>不得</b>被改动 ——
     * 终态是权威事实，接管方没有资格改写它。
     *
     * @param tenantId      租户标识
     * @param runId         根 Run 标识
     * @param newFencingToken 新世代的世代令牌
     * @return 被提升的在途记录条数
     */
    int adoptInFlight(@NonNull String tenantId,
                      @NonNull String runId,
                      long newFencingToken);

    /**
     * 终态写入结果。
     *
     * <p>⚠️ <b>为什么必须三态而不是 boolean</b>：{@code false} 把
     * 「已经是终态（幂等重放）」和「fencing token 过期（旧世代越权提交）」
     * 混成了一件事 ——
     * 前者应当返回<b>既有终态</b>（幂等），后者必须<b>拒绝承认</b>本次结果
     * （执行可能已发生、结果不被承认 → 需对账）。
     * 合并成一个布尔值，会让旧世代的过期提交被当成「幂等重放」而静默接受。
     */
    enum TerminalWriteOutcome {

        /** 本次写入成功。 */
        WRITTEN,

        /**
         * 记录已是终态（幂等重放）—— <b>不得覆盖</b>既有终态。
         *
         * <p>调用方应返回既有终态作为结论。
         */
        ALREADY_TERMINAL,

        /**
         * fencing token 过期：提交者不是当前世代（MA-T12）。
         *
         * <p>⚠️ 此时<b>不得承认本次结果</b>：子执行可能已经发生（副作用可能已存在），
         * 但结论不是权威事实 → 对外只能报「结果不确定」（需对账）。
         */
        TOKEN_EXPIRED,

        /**
         * 记录不存在（被清理或从未落库）。
         *
         * <p>⚠️ 同样不得承认本次结果：没有可对账的记录行。
         */
        NOT_FOUND;
    }
}
