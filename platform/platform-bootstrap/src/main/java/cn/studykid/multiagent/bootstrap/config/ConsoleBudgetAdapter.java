package cn.studykid.multiagent.bootstrap.config;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.ContextAssembler.AssemblyInput;
import cn.studykid.multiagent.context.ContextBudgetException;
import cn.studykid.multiagent.context.ContextPolicy;
import cn.studykid.multiagent.context.ContextPolicyRejectedException;
import cn.studykid.multiagent.context.ContextPolicyResolver;
import cn.studykid.multiagent.context.EffectivePolicyProjection;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.RequestContextProjection;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetException;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetException.Reason;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort;
import cn.studykid.multiagent.ports.context.ContextSpec;
import cn.studykid.multiagent.ports.context.ModelCapabilities;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;

/**
 * L3 ConsoleChat 预算保护的<b>唯一组合根适配实现</b>（ADR-013 D52 / M9-a）。
 *
 * <p>⚠️ <b>本类是生产链路里唯一允许把 {@link ContextAssembler} 接到 Console 的地方</b>。
 * agent-api 只认识 {@link ConsoleBudgetPort} 中性端口，不认识算法层类型（ADR-011）；
 * 算法层也不认识 Spring / JDBC。组合根把两侧接起来，任何一侧独立演化都不会把框架污染渗进内核。
 *
 * <p>⚠️ <b>关闭等价是构造级保证</b>：{@code GlobalContextPolicy.enabled()==false} 时
 * <b>第一行就返回</b> {@link BudgetResult#passthrough}——零策略解析、零模型能力读取、
 * 零预算、零管线、零布局、零摘要、零计数。Controller 拿回传入
 * {@code legacyRequest} 的<b>同一引用</b>，与「没有压缩功能」逐位一致（[D11] / G1）。
 *
 * <p>⚠️ <b>L3 独立协议</b>（D49④）：无 Agent 定义、无 Run、无租约、无 checkpoint、
 * 无 carry-over；因此⛔ 不复用 {@code RequestAssemblyPort}，也⛔ 不读
 * {@code cap_agent_def}。策略由平台级 {@link ContextPolicy#defaults()} + 强制
 * {@link ContextPolicy.Strategy#TRIM_ONLY} + 模型目录精确能力裁决得到，
 * <b>只跑 W1/W2，零 LLM</b>；{@code summarizer} 仅用于满足内核入参，TRIM_ONLY 永不调用。
 *
 * <p>⚠️ <b>无会话态</b>：每次请求都从 {@code ModelRequest.messages()} 全量历史独立投影，
 * {@code upToSeq=-1}（没有「已吸收游标」这种跨请求状态）、{@code priorCompactions=0}、
 * {@code carryOverStateJson=null}。端口是单例但零可变字段。
 *
 * <p>⚠️ <b>稳定前缀与不可信块</b>：{@code systemPrompt} 逐字取自 legacy 请求，封闭在
 * {@link ContextSpec#systemPrompt()} 中，⛔ 不追加任何动态内容；W1/W2 的裁剪说明只能由
 * {@code ContextLayout} 包成围栏 USER 消息。RAG / 记忆块对 L3 恒空。
 *
 * <p>⚠️ <b>fail-closed</b>：算法层拒因逐码投影到 {@link Reason}；兜底异常一律
 * {@code CONSOLE_BUDGET_FAILED}；⛔ 绝不 catch 后返回 legacy，
 * 否则开启态配错策略会变成「不报错、只是悄悄超窗」。
 */
public final class ConsoleBudgetAdapter implements ConsoleBudgetPort {

    /**
     * L3 的中立作用域哨兵：只给 {@link ContextSpec} 的必填身份字段占位，
     * <b>不是租户 id</b>，永不进请求正文、永不写 RLS 表、永不冒充 {@code TenantContext}。
     */
    static final String CONSOLE_SCOPE = ConsoleBudgetPort.DEFAULT_SCOPE;

    /** 参与 policyVersion 派生的稳定虚拟版本：L3 无 Agent 定义，也不能读当前已发布版本。 */
    private static final String CONSOLE_AGENT_VERSION = "console-l3";

    private final @NonNull GlobalContextPolicy global;
    private final @NonNull ContextAssembler assembler;
    private final @NonNull Summarizer summarizer;
    private final @Nullable ModelCatalogPort catalog;

    /**
     * @param assembler 组合根持有的<b>唯一</b>装配内核 Bean。
     *                  ⛔ adapter 内部不得再 {@code new ContextAssembler(...)}：
     *                  两套内核并存会让「改了 yml 阈值却没生效」变成静默失真。
     * @param summarizer 仅满足内核入参契约；TRIM_ONLY 永不调用，生产注入 NoOp。
     */
    public ConsoleBudgetAdapter(
            @NonNull GlobalContextPolicy global,
            @NonNull ContextAssembler assembler,
            @NonNull Summarizer summarizer,
            @Nullable ModelCatalogPort catalog) {
        this.global = Objects.requireNonNull(global, "global 不得为 null");
        this.assembler = Objects.requireNonNull(assembler, "assembler 不得为 null");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不得为 null");
        this.catalog = catalog;
    }

    @Override
    public @NonNull BudgetResult protect(@NonNull BudgetInput input) {
        Objects.requireNonNull(input, "input 不得为 null");
        ModelRequest legacy = input.legacyRequest();

        // ── [D11] 总熔断：零副作用短路，返回 legacy 同一引用 ──────────────
        if (!global.enabled()) {
            return BudgetResult.passthrough(legacy);
        }

        // ⚠️ 输入合法性只在启用态裁决：关闭态连「负数输出上限」都按 legacy 原样送，
        //    这才是 [D11] 的逐位等价；启用态则 fail-closed，绝不静默夹取成正数。
        if (input.maxOutputTokens() <= 0) {
            throw new ConsoleBudgetException(Reason.CONSOLE_BUDGET_INPUT_INVALID,
                    "maxOutputTokens 必须为正，实际=" + input.maxOutputTokens());
        }

        try {
            ModelCapabilities caps =
                    ContextPolicyResolutionSupport.capabilitiesOf(catalog, legacy.modelId());
            ContextPolicy base = ContextPolicy.defaults()
                    .withStrategy(ContextPolicy.Strategy.TRIM_ONLY);
            ContextPolicy effective = ContextPolicyResolver.resolve(new ContextPolicyResolver.Input(
                    base, caps, global, input.maxOutputTokens(), CONSOLE_AGENT_VERSION));

            // ⚠️ 档位是「只跑 W1/W2、零 LLM」的唯一允诺；被任何配置/投影改成 TIERED 即拒绝。
            if (effective.strategy() != ContextPolicy.Strategy.TRIM_ONLY) {
                throw new ConsoleBudgetException(Reason.CONTEXT_POLICY_INVALID,
                        "L3 Console 预算保护只支持 TRIM_ONLY，实际为 " + effective.strategy());
            }

            MessageProjection projected = projectMessages(legacy);
            ContextSpec spec = new ContextSpec(
                    CONSOLE_SCOPE,
                    input.scopeKey(),
                    legacy.modelId(),
                    legacy.systemPrompt(),
                    RequestContextProjection.canonicalToolSchemas(legacy.toolSchemas()),
                    "",
                    "",
                    List.of(),
                    projected.history(),
                    projected.currentInput(),
                    EffectivePolicyProjection.toEffectivePolicy(effective),
                    caps,
                    // L3 无 checkpoint / 无增量摘要游标：-1 表示全量历史都可按 recentTurns 判定。
                    -1,
                    null,
                    0);

            var out = assembler.assemble(new AssemblyInput(spec, legacy, summarizer, 0));

            // HARD / W3 都不是 L3 允许的装配结果；tier 只可能是 0/1/2。
            if (out.tier() > 2) {
                throw new ConsoleBudgetException(Reason.CONTEXT_POLICY_INVALID,
                        "L3 Console 预算保护不得产出 W3/HARD 档位，实际 tier=" + out.tier());
            }
            // ⚠️ 行为分流只认引用身份，⛔ 不在这里重写 enabled/strategy 判定。
            boolean budgeted = out.request() != legacy;
            if (!budgeted) {
                throw new ConsoleBudgetException(Reason.CONSOLE_BUDGET_FAILED,
                        "预算保护开启态未生成独立请求，adapter 空转");
            }
            return new BudgetResult(
                    out.request(), true, out.beforeTokens(), out.afterTokens(), out.tier());
        } catch (ConsoleBudgetException e) {
            throw e;
        } catch (ContextPolicyRejectedException e) {
            throw new ConsoleBudgetException(reasonOf(e.reason().name()), e.getMessage(), e);
        } catch (ContextBudgetException e) {
            throw new ConsoleBudgetException(reasonOf(e.reason().name()), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ConsoleBudgetException(Reason.CONSOLE_BUDGET_FAILED,
                    "L3 Console 预算保护失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把 Console 请求体已有的 {@code messages} 拆成「历史 + 当前输入」。
     *
     * <p>⚠️ 只在末条消息是<b>非空 USER</b>时才把它提升为 {@code currentInput}，
     * 并从 {@code history} 移除——否则 {@link cn.studykid.multiagent.context.ContextLayout}
     * 会在末尾再渲染一次，造成同一条用户输入重复发两遍（不报错，只是模型看到双份任务）。
     * 其余消息（包括 USER 锚点与 ASSISTANT/TOOL 轮次）按原序进 history；
     * {@code RoundSplitter} 保证 USER 永不裁、ASSISTANT+TOOL 整轮可裁。
     */
    private static @NonNull MessageProjection projectMessages(@NonNull ModelRequest legacy) {
        List<ModelMessage> all = legacy.effectiveMessages();
        if (all.isEmpty()) {
            return new MessageProjection(List.of(), "");
        }
        int last = all.size() - 1;
        ModelMessage tail = all.get(last);
        if (tail.role() == ModelMessage.Role.USER
                && tail.content() != null && !tail.content().isEmpty()) {
            return new MessageProjection(List.copyOf(all.subList(0, last)), tail.content());
        }
        return new MessageProjection(List.copyOf(all), "");
    }

    /** 算法层稳定拒因 → 中性稳定拒因；未知码一律兜底，绝不静默当成功。 */
    private static @NonNull Reason reasonOf(@NonNull String name) {
        try {
            return Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Reason.CONSOLE_BUDGET_FAILED;
        }
    }

    private record MessageProjection(@NonNull List<ModelMessage> history,
                                     @NonNull String currentInput) {
    }
}
