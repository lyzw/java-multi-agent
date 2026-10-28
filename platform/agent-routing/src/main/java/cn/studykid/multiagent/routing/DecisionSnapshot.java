package cn.studykid.multiagent.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.studykid.multiagent.core.idempotency.RequestDigest;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 一次路由决策的<b>可持久化快照</b>（10 号设计 §6.1，落 {@code agent_invocation.routing_decision}）。
 *
 * <p><b>为什么要专门一个类来序列化</b>：重放不变式
 * {@code (policyRef, canonicalInputSha256) → finalTarget} 是<b>可测性质</b>而不是愿望，
 * 而它成立的前提是「快照的写法只有一份」。发布期 dry-run、运行期委派、
 * 事后对账若各拼一遍 JSON，键名与哈希口径迟早漂移 ——
 * 漂移的症状不是报错，是<b>两条本该相等的快照不相等</b>，于是复核结论作废。
 *
 * <p>⚠️ <b>本类刻意不落 {@code canonicalInput} 明文</b>（对 §6.1 字段清单的一处收紧）：
 * <ul>
 *   <li>重放不变式只需要输入的 <b>SHA-256</b>，不需要输入本身；</li>
 *   <li>{@code agent_invocation} 的红线是「只存摘要不存明文」（与设计文档 §8 · G-1 同源）——
 *       {@code in.*} 是业务字段（订单号、学段、主题…），抄进 jsonb 等于为本表
 *       新开一条明文旁路，而这条旁路不在既有的脱敏与保留策略覆盖范围内。</li>
 * </ul>
 *
 * <p>⚠️ <b>轨迹里的 {@code error} 自由文本同样不落库</b>：
 * {@link PredicateEvaluator} 的失败原因会内嵌<b>实际取到的输入值</b>
 * （形如 {@code in.topic=三年级}），理由再有用也不能拿它的载体去换一条红线。
 * 落库的是 {@code ordinal}/{@code ruleId}/{@code outcome} ——
 * 「哪一条规则、判成了什么」足以定位到具体规则；可读原因只进日志。
 *
 * @param policyRef           策略固定版本引用（重放主键半边）
 * @param policyFingerprint   决策<b>当时</b>那份策略的内容指纹
 * @param decision            引擎给出的决策（目标与「为什么是这个目标」同批产出）
 * @param canonicalInputSha256 规范化输入的摘要（重放主键另半边）
 */
public record DecisionSnapshot(@NonNull String policyRef,
                               @NonNull String policyFingerprint,
                               @NonNull RoutingDecision decision,
                               @NonNull String canonicalInputSha256) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DecisionSnapshot {
        if (policyRef.isBlank()) {
            throw new IllegalArgumentException("policyRef 不能为空：重放不变式的主键半边");
        }
        if (policyFingerprint.isBlank()) {
            throw new IllegalArgumentException("policyFingerprint 不能为空：无法佐证「用的是哪一版内容」");
        }
        if (canonicalInputSha256.isBlank()) {
            throw new IllegalArgumentException("canonicalInputSha256 不能为空：重放不变式的另半边主键");
        }
    }

    /**
     * 由策略行 + 规范化输入 + 决策构造快照。
     *
     * @param policyRef         策略引用（{@code id@version}）
     * @param policyFingerprint 策略内容指纹
     * @param input             阶段① 的规范化输入（<b>只取其哈希</b>，不取明文）
     * @param decision          阶段②③④ 的决策
     * @return 快照
     */
    public static @NonNull DecisionSnapshot of(@NonNull String policyRef,
                                               @NonNull String policyFingerprint,
                                               @NonNull RoutingInput input,
                                               @NonNull RoutingDecision decision) {
        return new DecisionSnapshot(policyRef, policyFingerprint, decision,
                canonicalInputSha256(input));
    }

    /**
     * 规范化输入的 SHA-256 —— 重放不变式主键的另半边。
     *
     * <p>⚠️ 走 {@link RequestDigest}（与本表 {@code input_digest} <b>同一个</b>归一化实现）：
     * 键序无关、嵌套递归排序。若此处另写一份序列化，
     * 「同一输入两次委派得到同一摘要」就只在哈希函数层成立、在实现层不成立。
     *
     * <p>{@code ctx} 用 {@link CtxName#wireName()} 作键（封闭词汇表，六个），
     * 因此{@code ctx.now.utcDate} 也进摘要 ——
     * 引用了时间的策略其重放口径是「<b>同一 UTC 自然日内</b>可重放」，
     * 这个取舍必须由主键本身承载，而不是靠读者记得。
     */
    public static @NonNull String canonicalInputSha256(@NonNull RoutingInput input) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("in", input.in());
        Map<String, Object> ctx = new LinkedHashMap<>();
        input.ctx().forEach((name, value) -> ctx.put(name.wireName(), value));
        canonical.put("ctx", ctx);
        return RequestDigest.of(canonical).value();
    }

    /** 本次决策的目标（重放不变式的值半边）。 */
    public @Nullable String finalTarget() {
        return decision.finalTarget();
    }

    /**
     * 快照的 JSON 文本（进 {@code routing_decision jsonb}）。
     *
     * <p>键序固定为声明顺序，便于人读；内容稳定性由 {@code canonicalInputSha256} 保证，
     * 不靠 JSON 文本比对。
     *
     * @return JSON 文本
     */
    public @NonNull String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("policyRef", policyRef);
        json.put("policyFingerprint", policyFingerprint);
        json.put("mode", decision.mode().name());
        json.put("canonicalInputSha256", canonicalInputSha256);
        json.put("evaluationTrace", trace());
        json.put("resolution", decision.resolution().name());
        json.put("matchedRuleId", decision.matchedRuleId());
        json.put("matchedRuleSha256", sha256OrNull(decision.matchedRuleSignature()));
        json.put("candidateFromModel", decision.candidateFromModel());
        json.put("conflict", decision.conflict());
        json.put("finalTarget", decision.finalTarget());
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JacksonException e) {
            // ⚠️ 快照的每个值都是枚举名 / 哈希 / 标识，没有「序列化不了的输入」这条路；
            //    真走到这里说明代码写错了，必须炸出来 —— 悄悄返回 null 等于丢掉可复核性，
            //    而决策快照正是「这次委派凭什么」的唯一持久副本。
            throw new IllegalStateException("路由决策快照序列化失败: " + policyRef, e);
        }
    }

    /** 求值轨迹（只含实际判过的规则；<b>不含</b>内嵌输入值的自由文本原因）。 */
    private @NonNull List<Map<String, Object>> trace() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (EvaluationTraceEntry entry : decision.trace()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ordinal", entry.ordinal());
            item.put("ruleId", entry.ruleId());
            item.put("outcome", entry.outcome());
            entries.add(item);
        }
        return entries;
    }

    private static @Nullable String sha256OrNull(@Nullable String signature) {
        return signature == null ? null : RequestDigest.ofText(signature).value();
    }
}
