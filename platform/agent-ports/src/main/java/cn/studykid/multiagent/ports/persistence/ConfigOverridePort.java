package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 租户配置覆盖端口（P4-D15 / F6 · C2 配置存储 + C3 写入端点）。
 *
 * <p><b>⚠️ 本端口只管「覆盖值」，yml 仍是默认值的唯一来源</b>。
 * 读配置的完整语义是 {@code yml 默认 ⊕ DB 覆盖}，由
 * {@code EffectiveConfigResolver}（application 层）合并；
 * 本端口<b>只</b>暴露覆盖层本身，避免把「默认值来源」也搅进持久化。
 *
 * <p><b>⚠️ 生效机制不是「写完即改全局」</b>：覆盖值在
 * <b>下一次 Run 创建快照时</b>被读取（R-021 快照隔离）。
 * 在途 Run 的行为<b>不受影响</b>——这是既有架构的正确行为，不是缺陷。
 *
 * <p><b>⚠️ 收紧约束不在本端口</b>：安全语义（只能收紧）的判定权威在
 * application 层的 {@code ConfigTighteningPolicy}，
 * 本端口只负责「执行 + 留痕」。这样 application 可复用既有
 * {@code ApprovalRequirement.isRelaxationTo}，不重复实现安全语义。
 *
 * <p><b>⚠️ 审计 fail-closed</b>：写入与清除均走
 * {@link AuditPort#recordRequired}，审计失败即中止——
 * 无审计的配置变更等于无声变更（P3-D07 的核心纪律）。
 */
public interface ConfigOverridePort {

    /**
     * 可写配置键目录（白名单）。
     *
     * <p><b>⚠️ 为什么是白名单而非「不在黑名单即可写」</b>：沿用项目铁律
     * 「序列化敏感数据用白名单不用黑名单」。新增配置键若忘了加进本目录，
     * 默认是<b>不可写</b>（安全方向）；若用黑名单，新增键会自动变成可写
     * ——那意味着某天有人往 yml 加了个 {@code jwt.private-key} 就自动暴露了写入端点。
     *
     * <p><b>⚠️ 键名必须与 yml 逐位一致</b>：本目录是 yml 键的<b>镜像</b>，
     * 不是新的命名空间。键名漂移会导致「写了却不生效」——
     * 而只读端点仍显示新值，正是 P4-D15 §7 反证 S-4 要防的危险形态。
     *
     * <p><b>L1 锁定键刻意不在此目录</b>：工具风险级别（代码事实）与审批人范围
     * （DEC-6 合规边界）本就不可写，不在本体系内（见 P4-D15 §6 L-4）。
     */
    Map<String, Tightening> WRITABLE = Map.ofEntries(
            // ── 运行上限：只能变小（放宽 = 烧钱 / 过载）──
            Map.entry("agent.run.max-cost-per-run", Tightening.TIGHTEN),
            Map.entry("agent.run.max-llm-calls", Tightening.TIGHTEN),
            Map.entry("agent.run.max-steps", Tightening.TIGHTEN),
            Map.entry("agent.tenant.max-concurrent-runs", Tightening.TIGHTEN),
            Map.entry("deployment.pilot.max-tenants", Tightening.TIGHTEN),
            // ── 限流与背压：只能变小 ──
            Map.entry("agent.rate-limit.limit-per-window", Tightening.TIGHTEN),
            Map.entry("agent.worker.concurrency", Tightening.TIGHTEN),
            Map.entry("agent.backpressure.high-watermark", Tightening.TIGHTEN),
            // ── 保留期：只能变短（延长 = 违规留存）──
            Map.entry("agent.retention.profile-months", Tightening.TIGHTEN),
            Map.entry("agent.retention.prompt-days", Tightening.TIGHTEN),
            Map.entry("agent.retention.trace-days", Tightening.TIGHTEN),
            Map.entry("agent.retention.audit-days", Tightening.TIGHTEN),
            // ── 审批：TTL 只能缩短；required-by-risk 只能按等级收紧 ──
            Map.entry("agent.approval.ttl", Tightening.TIGHTEN),
            Map.entry("agent.approval.required-by-risk", Tightening.TIGHTEN_RATCHET));

    /**
     * 读取某租户的全部配置覆盖。
     *
     * <p>返回<b>仅含被覆盖的键</b>（无覆盖的键不出现），空 Map 表示该租户无任何覆盖。
     *
     * @param tenantId 租户（RLS 绑定依据）
     */
    @NonNull Map<String, OverrideEntry> overridesFor(@NonNull String tenantId);

    /**
     * 写入一条配置覆盖（同键覆盖写）。
     *
     * @param tenantId 租户
     * @param key      配置键（须在 {@link #WRITABLE} 目录内）
     * @param value    新值文本（按 {@link OverrideEntry#valueType()} 解析）
     * @param reason   CHG 变更理由（<b>不少于 10 字</b>，C7）
     * @param actorId  操作者（来自认证上下文，<b>不得</b>取自请求体）
     * @throws IllegalArgumentException 键不可写 / 值格式非法 / 理由过短 → 端点转 400
     * @throws UnknownConfigKeyException 键不在可写目录 → 端点转 404
     */
    @NonNull OverrideEntry apply(@NonNull String tenantId,
                                  @NonNull String key,
                                  @NonNull String value,
                                  @NonNull String reason,
                                  @NonNull String actorId)
            throws UnknownConfigKeyException;

    /**
     * 清除一条覆盖（回落到 yml 默认值）。
     *
     * @throws UnknownConfigKeyException 键不在可写目录
     */
    void clear(@NonNull String tenantId, @NonNull String key, @NonNull String actorId)
            throws UnknownConfigKeyException;

    /** 一条配置覆盖。 */
    record OverrideEntry(
            @NonNull String key,
            @NonNull String value,
            @NonNull String valueType,
            /** 收紧方向：{@code TIGHTEN} 收紧 / {@code NEUTRAL} 中性 / 不可写键不会出现 */
            @NonNull Tightening tightening,
            @Nullable String reason,
            @Nullable String changedBy,
            @Nullable Instant updatedAt) {
    }

    /** 收紧方向（与 {@code ApprovalRequirement.strictness} 同构）。 */
    enum Tightening {
        /** 只能收紧（上限类 / TTL）。 */
        TIGHTEN,
        /** 中性（与当前值无关，但可写）。 */
        NEUTRAL,
        /** 只能收紧（审批要求，按等级）。 */
        TIGHTEN_RATCHET
    }

    /** 可写目录中不存在的配置键（→ 404）。 */
    class UnknownConfigKeyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String key;

        public UnknownConfigKeyException(String key) {
            super("配置键不可写或不存在: " + key);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}