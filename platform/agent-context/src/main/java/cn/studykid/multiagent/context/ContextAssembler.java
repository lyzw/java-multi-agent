package cn.studykid.multiagent.context;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.context.WaterLevel.Tier;
import cn.studykid.multiagent.ports.context.AssembledContext;
import cn.studykid.multiagent.ports.context.ContextSpec;
import cn.studykid.multiagent.ports.context.EffectiveContextPolicy;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * 三链路<b>统一的上下文装配内核</b>（ADR-013 D38 / 开发计划 §6.4-②，M7-a）。
 *
 * <p>⚠️ <b>本类是「请求变换器」而非「请求构造器」</b>：M6 冻结的 {@link ContextSpec} 刻意
 * 只承载六块上下文，<b>不含</b> {@code ModelRequest} 的非上下文字段
 * （{@code temperature / maxTokens / timeout / toolSchemas(Map) / userContent / modelId}）。
 * 若装配器试图仅凭 {@code ContextSpec} 重建请求，这些字段必然被填成默认值 ——
 * 关闭态就不再是「与没有压缩功能逐位一致」，而是「悄悄换了一套请求参数」（[D11] 失真）。
 * 故本内核的入参同时携带一条<b>由调用方按 legacy 路径完整构造好的 {@link ModelRequest}</b>：
 * 它既是关闭态逐位等价的<b>基线</b>，也是开启态非上下文字段的<b>唯一真源</b>。
 *
 * <p>⚠️ <b>关闭等价桥 [D11]</b>：{@code enabled=false}（或策略档为 {@code OFF}）时，
 * 本内核<b>原样返回传入的同一个 {@link ModelRequest} 对象</b>（同一引用，逐位一致），
 * ⛔ 不分配预算、不跑管线、不渲染布局、不调用摘要器、不注册指标、不发事件。
 * 逐位一致是<b>构造级保证</b>（返回同一引用），而不是「再实现一遍 legacy trim」——
 * 后者会复制一份裁剪逻辑，与 {@code ContextBudgetTrimmer} 漂移时静默改字节。
 * 调用方（M7-b 接线）仍按现状跑 {@code ContextBudgetTrimmer} + 记忆/裁剪说明拼装，
 * 产出的请求原样交给本内核；故关闭态与今天<b>完全相同</b>。
 *
 * <p>⚠️ <b>开启态只切换上下文字段</b>：先映射策略、算全窗口预算、跑
 * {@link CompactionPipeline}（W1/W2/W3）、经 {@link ContextLayout} 渲染稳定前缀 + 围栏块，
 * 再构造新请求 —— {@code systemPrompt} / {@code messages} 来自渲染结果，
 * 其余 6 个非上下文字段逐字复制自基线请求。systemPrompt 逐字等于 {@code spec.systemPrompt()}
 * （⛔ 绝不追加记忆 / 裁剪说明 / 摘要），不可信块全部由 ContextLayout 包成围栏 USER 消息。
 *
 * <p>⚠️ <b>纯函数 / 零接线</b>：零 Spring / 零 JDBC / 零 IO / 零时钟 / 零网关。
 * W3 摘要能力由外部注入的 {@link Summarizer} 提供，本层不调模型。
 * M7-a 阶段<b>不被任何生产模块引用</b>；checkpoint 装载/落盘与 carry-over 校验属
 * S4 / M8，本内核不消费 {@code carryOverStateJson} / {@code fencingToken} / {@code scopeKey}，
 * 仅通过入参承载供上层观测与落点使用。
 */
public final class ContextAssembler {

    private final @NonNull TokenCounter counter;
    private final @NonNull Thresholds thresholds;

    public ContextAssembler(@NonNull TokenCounter counter, @NonNull Thresholds thresholds) {
        this.counter = Objects.requireNonNull(counter, "ContextAssembler.counter 不得为 null");
        this.thresholds = Objects.requireNonNull(thresholds, "ContextAssembler.thresholds 不得为 null");
    }

    /**
     * 一次装配的全部入参。全参构造唯一、逐字段 fail-closed（铁律 #10/#11）。
     *
     * @param spec             六块上下文 + 已裁决策略 + 模型能力 + 增量游标 + 租约（M6 中性载体）
     * @param legacyRequest    调用方按 legacy 路径完整构造的请求：关闭态逐位等价基线 + 非上下文字段真源
     * @param summarizer       W3 增量摘要器（外部注入纯函数；TRIM_ONLY / W0 不会被调用）
     * @param priorCompactions 本 Run 此前已发生的压缩次数（单次上限由上层按 policy 裁决）
     */
    public record AssemblyInput(
            @NonNull ContextSpec spec,
            @NonNull ModelRequest legacyRequest,
            @NonNull Summarizer summarizer,
            int priorCompactions) {

        public AssemblyInput {
            Objects.requireNonNull(spec, "AssemblyInput.spec 不得为 null");
            Objects.requireNonNull(legacyRequest,
                    "AssemblyInput.legacyRequest 不得为 null（关闭等价桥基线）");
            Objects.requireNonNull(summarizer, "AssemblyInput.summarizer 不得为 null");
            if (priorCompactions < 0) {
                throw new IllegalArgumentException("AssemblyInput.priorCompactions 不得为负");
            }
        }
    }

    /**
     * 是否已启用压缩。唯一权威判定：全局 / Agent / 策略档三层已被
     * {@link ContextPolicyResolver} 裁决进 {@code EffectiveContextPolicy}，
     * 这里只做「启用 且 非 OFF」的 fail-safe 复核（不一致时宁可走关闭态逐位等价）。
     */
    public static boolean isEnabled(@NonNull EffectiveContextPolicy policy) {
        Objects.requireNonNull(policy, "isEnabled 入参不得为 null");
        return policy.enabled() && !"OFF".equals(policy.strategy());
    }

    /**
     * 装配一次模型请求。关闭态返回基线请求同一引用；开启态只切换上下文字段。
     */
    public @NonNull AssembledContext assemble(@NonNull AssemblyInput input) {
        Objects.requireNonNull(input, "assemble 入参不得为 null");
        ContextSpec spec = input.spec();
        ModelRequest legacy = input.legacyRequest();

        // ── 关闭等价桥 [D11]：原样返回同一引用，零预算 / 零管线 / 零布局 / 零摘要 ──
        if (!isEnabled(spec.policy())) {
            return new AssembledContext(legacy, null, 0, false, 0, 0, input.priorCompactions());
        }

        // ── 开启态：逐字段映射已裁决策略（不在此处重新裁决 / 不读当前配置）──
        ContextPolicy policy = EffectivePolicyProjection.toContextPolicy(spec.policy());

        // ── 全窗口预算 + 四级水位线管线 + 稳定前缀布局 ──
        CompactionPipeline.Input pipeInput = new CompactionPipeline.Input(
                spec.systemPrompt(), spec.toolSchemas(), spec.firstUserTurn(),
                spec.ragBlocks(), spec.memoryBlock(), spec.messages(),
                spec.currentInput(), spec.upToSeq(), null);
        ContextBudget budget = BudgetAllocator.allocate(policy, counter,
                new BudgetAllocator.FixedBlocks(spec.systemPrompt(), spec.toolSchemas(),
                        spec.firstUserTurn(), spec.currentInput()));
        CompactionResult result = new CompactionPipeline(counter)
                .compact(pipeInput, budget, policy, thresholds, input.summarizer());
        RenderedContext rendered = ContextLayout.render(result.blocks());

        // ── 只切换上下文字段；6 个非上下文字段逐字复制自基线请求 ──
        //    ⚠️ toolSchemas 用 legacy 的 Map（真正送模型的定义）；spec.toolSchemas() 只用于
        //       预算计量。调用方（M7-b 适配器）须保证两者同源于一份工具定义。
        //    ⚠️ userContent 在 messages 非空时是冗余字段，逐字保留基线值，不发新字节。
        ModelRequest assembled = new ModelRequest(
                legacy.modelId(),
                rendered.systemPrompt(),
                legacy.userContent(),
                legacy.temperature(),
                legacy.maxTokens(),
                legacy.timeout(),
                legacy.toolSchemas(),
                rendered.messages());

        int compactionCount = input.priorCompactions() + (result.compacted() ? 1 : 0);
        // checkpointDelta 为 null：M7-a 只做装配，轮末 checkpoint 落点留给 M7-b / S4。
        return new AssembledContext(assembled, null, tierCode(result.tier()),
                result.fallback(), result.beforeTokens(), result.afterTokens(), compactionCount);
    }

    /** {@link Tier} → 稳定档位码 0/1/2/3（与 {@link AssembledContext} 契约一致，不靠 ordinal 漂移）。 */
    private static int tierCode(@NonNull Tier tier) {
        return switch (tier) {
            case W0 -> 0;
            case W1 -> 1;
            case W2 -> 2;
            case W3 -> 3;
            case HARD -> throw new IllegalStateException(
                    "HARD 水位不得作为装配结果返回（预算 fail-closed 异常应先终止）");
        };
    }
}
