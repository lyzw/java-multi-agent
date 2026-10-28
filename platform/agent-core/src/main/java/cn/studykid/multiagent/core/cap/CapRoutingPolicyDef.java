package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.ConflictPolicy;
import cn.studykid.multiagent.core.orchestration.NoMatchAction;
import cn.studykid.multiagent.core.orchestration.RoutingMode;

/**
 * 路由策略的定义行（10 号设计 §4.2 · 取代第十四批的「策略枚举 + 有序目标列表」）。
 *
 * <p>⚠️ <b>路由策略是配置对象，不是运行期数据</b>：它描述「路由 Agent 按什么规则
 * 把工作交给哪些子 Agent」，发布期固化、运行期只读。
 * 与输出契约同构 —— 配置对象与运行期数据必须分表，
 * 否则「运行数据覆盖配置定义」会让路由行为不可复核。
 *
 * <p>⚠️ <b>必须固定版本</b>：Agent 的 {@code routingPolicyRef} 形如 {@code id@version}，
 * 禁止 {@code latest} 漂移别名 —— 与子 Agent 引用、输出契约引用同口径。
 * 策略改了就是新版本，旧版本仍可被历史 Run 按快照引用。
 *
 * <p>⚠️ <b>本行存的是策略<b>文本</b>，不是解析结果</b>：
 * {@code inputSchema} / {@code evaluation} / {@code sampleSet} 三段是 JSON 原文，
 * 文法与语义由 {@code agent-routing} 的 {@code PolicyCodec} + {@code PublishGuards} 判定 ——
 * 领域层不认得谓词文法，否则「策略里能写什么」就有了第二个真相，
 * 而第二个真相迟早和引擎不一致（10 号设计 §7 债 5 同一个理由）。
 * 因此本记录只做<b>行级</b>一致性（非空、兜底目标与处置动作必须同时存在），
 * 规则条数、谓词文法、DNF 预算一概不在这里判。
 *
 * <p>⚠️ <b>标识是可读字符串，不是 UUID</b>：对外 schema 契约
 * （{@code common.schema.json#/$defs/id}）把 {@code fixedVersionRef.id} 定义为
 * 可读字符串（形如 {@code orders-router}），治理通道也按字符串接收。
 * 参见 V23 迁移的教训：把新概念的 id 声明成 UUID 会让整笔发布事务在
 * 非 UUID id 上回滚，而这种缺陷在 mock 连接的单元测试里<b>永远不会暴露</b>。
 *
 * @param tenantId          租户标识
 * @param policyId          策略标识（逻辑 id · 可读字符串）
 * @param version           策略版本号（固定引用）
 * @param mode              谁选目标（{@code RULES_ONLY / RULE_FIRST / SEMANTIC}）
 * @param inputSchema       路由输入词汇表（JSON 原文 · 非空）
 * @param evaluation        有序规则列表（JSON 数组原文 · 非空数组由 {@code RULES_ONLY/RULE_FIRST} 强制）
 * @param defaultTarget     兜底目标；{@code noMatchAction=DEFAULT_TARGET} 时<b>必填</b>
 * @param noMatchAction     无命中处置（{@code REJECT / DEFAULT_TARGET}）
 * @param onConflict        规则与模型候选冲突时的裁决（{@code RULE_WINS / CANDIDATE_WINS}）
 * @param sampleSet         发布期回放样本（JSON 数组原文，可为 {@code []}）
 * @param configFingerprint 内容指纹（判定「策略变了没有」）
 * @param schemaVersion     行格式版本
 * @param createdAt         创建时间
 * @param publishedAt       发布时间；{@code null} = 草稿（<b>不可被引用</b>）
 * @param lifecycleState    生命周期状态（§6.6）；{@code null} = <b>未记录</b>（≠ 不是 DEPRECATED）
 */
public record CapRoutingPolicyDef(
        @NonNull String tenantId,
        @NonNull String policyId,
        int version,
        @NonNull RoutingMode mode,
        @NonNull String inputSchema,
        @NonNull String evaluation,
        @Nullable String defaultTarget,
        @NonNull NoMatchAction noMatchAction,
        @NonNull ConflictPolicy onConflict,
        @NonNull String sampleSet,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt,
        @Nullable ConfigLifecycleState lifecycleState) {

    public CapRoutingPolicyDef {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId 不能为空：决策快照的 policyRef 要有可读标识");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须为正数，收到: " + version);
        }
        if (mode == null || noMatchAction == null || onConflict == null) {
            throw new IllegalArgumentException("mode / noMatchAction / onConflict 不能为空"
                    + "（缺一个就得由代码猜，而猜出来的路由行为不在任何策略文本里）");
        }
        if (isBlank(inputSchema) || isBlank(evaluation) || isBlank(sampleSet)) {
            // ⚠️ 空文本 ≠ 空集合：读回 null 与读回 "[]" 必须是两件事，
            //    否则「库里这行没写」会在运行期被当成「策略声明了零条规则」。
            throw new IllegalArgumentException("inputSchema / evaluation / sampleSet 不能为空文本"
                    + "（无样本就写 []，不要留空）");
        }
        if (mode.requiresNonEmptyRules() && isEmptyArray(evaluation)) {
            throw new IllegalArgumentException(mode + " 策略的 evaluation 不能是空数组："
                    + "空规则等于「每次都走兜底」，那是配置缺陷而不是路由行为"
                    + "（与 DDL 的 cap_routing_policy_eval_ck 同口径）");
        }
        if (noMatchAction == NoMatchAction.DEFAULT_TARGET && isBlank(defaultTarget)) {
            throw new IllegalArgumentException("no_match_action=DEFAULT_TARGET 必须声明 default_target"
                    + "（与 DDL 的 default_target_ck 同口径，两处都得拒才不会出现库能存、代码造不出）");
        }
        if (configFingerprint == null || configFingerprint.isBlank()) {
            throw new IllegalArgumentException("configFingerprint 不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
        sampleSet = sampleSet.trim();
    }

    /**
     * @return 是否为已发布版本（草稿不可被 Agent 引用）
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * @return 是否已被<b>停用</b>（{@code DEPRECATED}）
     *
     * <p>⚠️ 「未记录」不等于「未停用」，但也不等于「已停用」：
     * {@code lifecycleState == null} 表示「这条记录没有携带生命周期状态」。
     * 引用判定必须同时看 {@link #isPublished()} 与本方法。
     */
    public boolean isDeprecated() {
        return lifecycleState == ConfigLifecycleState.DEPRECATED;
    }

    /**
     * @return 固定版本引用（{@code id@version}），也是决策快照的 {@code policyRef}
     */
    public @NonNull String ref() {
        return policyId + "@" + version;
    }

    /**
     * 完整的策略文本（§4.1 的对象形态）—— 交给 {@code PolicyCodec} 解析的唯一入口素材。
     *
     * <p>⚠️ <b>由行字段拼回对象、而不是另存一份原文</b>：
     * 若再开一列存「策略原文」，改规则时两处不同步，校验的就不是运行的那份东西。
     *
     * @return 策略 JSON 文本
     */
    public @NonNull String definitionJson() {
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append("\"policyId\":").append(quote(policyId)).append(',')
                .append("\"version\":").append(version).append(',')
                .append("\"mode\":").append(quote(mode.name())).append(',')
                .append("\"inputSchema\":").append(inputSchema).append(',')
                .append("\"evaluation\":").append(evaluation).append(',');
        if (defaultTarget != null && !defaultTarget.isBlank()) {
            json.append("\"defaultTarget\":").append(quote(defaultTarget)).append(',');
        }
        json.append("\"noMatchAction\":").append(quote(noMatchAction.name())).append(',')
                .append("\"onConflict\":").append(quote(onConflict.name())).append(',')
                .append("\"sampleSet\":").append(sampleSet)
                .append('}');
        return json.toString();
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }

    /**
     * 粗判「空数组」。这里<b>不做 JSON 解析</b>：领域层不引入 JSON 依赖，
     * 真正的文法判定在 {@code PolicyCodec}；本检查只求「明显是空的一定要拦住」。
     */
    private static boolean isEmptyArray(@NonNull String text) {
        String trimmed = text.replaceAll("\\s", "");
        return "[]".equals(trimmed);
    }

    private static @NonNull String quote(@NonNull String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
