package cn.studykid.multiagent.context;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 会话级滚动结构化状态（《方案》§5.3 W3 / §5.4 / §6.3，M5-c）。
 *
 * <p>⚠️ <b>只含结构化字段，绝不含原始对话正文</b>：字段来自 W3 抽取器对用户纠正 / 约束 /
 * 负向知识 / 精确数值 / 工具引用的结构化提炼。SSE carry-over 把它作为 opaque base64
 * 的载荷（§4.4），DB checkpoint 把它写进 {@code structured_state jsonb}（§5.6）——
 * 两者都必须先经本 record 的确定性序列化，绝不让「模型自由文本」混入。
 *
 * <p>⚠️ <b>全参构造是唯一构造 + 唯一拷贝点</b>（铁律 #11 / 项目事故）：本 record
 * 不提供兼容构造器；新增字段时所有拷贝点（{@link RollingStateMerger} 的确定性合并）
 * 会因签名变化编译失败，强制逐字段带过，杜绝「静默清空后加的字段」。
 *
 * <p>⚠️ <b>集合顺序即语义</b>：{@code constraints}/{@code userCorrections} 等按
 * 「旧值原序 + 新值去重追加」排列（§4.3 增量 merge），顺序稳定是确定性 JSON 与
 * checkpoint 摘要一致的前提。任何重排都会让同一状态生成不同摘要 → 幂等键失效。
 *
 * @param goal                当前目标（可空；W3 抽取器只在有新目标时覆盖）
 * @param constraints         显式约束（"不要…" / "必须…"，逐字引用原文）
 * @param userCorrections     用户纠正 / 负向知识（逐字引用原文）
 * @param decisions           已做决定
 * @param rejectedApproaches  已否决方案（防重复尝试）
 * @param openItems           未决事项
 * @param artifacts           外部产物引用（{@code refId} 可回取原文）
 * @param facts               精确事实（知识点 / 错误类型 / 数值 / UUID，逐字）
 * @param toolCalls           工具调用摘要（{@code resultRef} 可回取原文）
 * @param fromSeq             状态覆盖的最早 seq（含）
 * @param upToSeq             单调游标：已吸收到的 seq（含），checkpoint 的 {@code to_seq}
 * @param policyVersion       策略指纹；不匹配即 fail-closed 丢弃重建（§4.3）
 */
public record StructuredState(
        @NonNull String goal,
        @NonNull List<String> constraints,
        @NonNull List<String> userCorrections,
        @NonNull List<String> decisions,
        @NonNull List<String> rejectedApproaches,
        @NonNull List<String> openItems,
        @NonNull List<Artifact> artifacts,
        @NonNull List<Fact> facts,
        @NonNull List<ToolCall> toolCalls,
        long fromSeq,
        long upToSeq,
        @NonNull String policyVersion) {

    public StructuredState {
        goal = goal == null ? "" : goal;
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        userCorrections = userCorrections == null ? List.of() : List.copyOf(userCorrections);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        rejectedApproaches = rejectedApproaches == null ? List.of() : List.copyOf(rejectedApproaches);
        openItems = openItems == null ? List.of() : List.copyOf(openItems);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        facts = facts == null ? List.of() : List.copyOf(facts);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        policyVersion = policyVersion == null ? "" : policyVersion;
    }

    /** 空状态：无正文、无字段，游标在 -1（尚未吸收任何 seq）。 */
    public static @NonNull StructuredState empty() {
        return new StructuredState("", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), 0, -1, "");
    }

    /** 是否尚未吸收任何状态（{@link #empty()} 的语义判定）。 */
    public boolean isEmpty() {
        return goal.isEmpty() && constraints.isEmpty() && userCorrections.isEmpty()
                && decisions.isEmpty() && rejectedApproaches.isEmpty() && openItems.isEmpty()
                && artifacts.isEmpty() && facts.isEmpty() && toolCalls.isEmpty();
    }

    /** 外部产物引用：{@code refId} 指向 run_message / chunk，可按需回取原文。 */
    public record Artifact(@NonNull String refId, @NonNull String kind, @NonNull String oneLine) {
        public Artifact {
            refId = refId == null ? "" : refId;
            kind = kind == null ? "" : kind;
            oneLine = oneLine == null ? "" : oneLine;
        }
    }

    /** 精确事实：{@code key} 去重，{@code value} 逐字保留（禁止近似改写）。 */
    public record Fact(@NonNull String key, @NonNull String value) {
        public Fact {
            key = key == null ? "" : key;
            value = value == null ? "" : value;
        }
    }

    /** 工具调用摘要：{@code resultRef} 指向原始工具结果，可回取。 */
    public record ToolCall(@NonNull String name, @NonNull String resultRef, @NonNull String oneLine) {
        public ToolCall {
            name = name == null ? "" : name;
            resultRef = resultRef == null ? "" : resultRef;
            oneLine = oneLine == null ? "" : oneLine;
        }
    }
}
