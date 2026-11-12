package cn.studykid.multiagent.bootstrap.config;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.application.cap.ContextPolicyReader;
import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.ContextAssembler.AssemblyInput;
import cn.studykid.multiagent.context.ContextBudgetException;
import cn.studykid.multiagent.context.ContextPolicy;
import cn.studykid.multiagent.context.ContextPolicyRejectedException;
import cn.studykid.multiagent.context.ContextPolicyResolver;
import cn.studykid.multiagent.context.EffectivePolicyProjection;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.RequestContextProjection;
import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.ports.context.AssembledContext;
import cn.studykid.multiagent.ports.context.ContextSpec;
import cn.studykid.multiagent.ports.context.ModelCapabilities;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException.Reason;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyInput;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyResult;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * M7-b 统一请求装配适配层：把 {@code agent-ports} 的中性入口接到
 * {@code agent-context} 的纯算法内核上（ADR-013 D38 / G1 A.5 升级台账）。
 *
 * <p>⚠️ <b>本类是生产链路里唯一允许引用 {@code ContextAssembler} 的地方</b>。
 * worker 只认识中性端口，不认识算法层类型（ADR-011 / M7-a 守卫边界）；
 * 算法层也不认识 Spring / JDBC（S1 守卫）。组合根把两侧接起来，
 * 任何一侧独立演化都不会把框架污染渗进纯算法层。
 *
 * <p>⚠️ <b>关闭等价是构造级保证</b>：{@code GlobalContextPolicy.enabled()==false} 时
 * <b>第一行就返回</b> {@link RequestAssemblyResult#passthrough}——零策略解析、零能力读取、
 * 零预算、零管线、零布局、零摘要、零事件、零指标。worker 侧拿回的是传入
 * {@code legacyRequest} 的<b>同一引用</b>，与「没有压缩功能」逐位一致（[D11] / G1）。
 *
 * <p>⚠️ <b>冻结策略来源 [D4]</b>：按调用方给出的 {@code agentId + agentVersion}
 * 精确读 {@code cap_agent_def} 的 {@code generationParams} 一等列。该版本由
 * {@code execution_capability_snapshot} 固定，⛔ 绝不读「当前已发布版本」——
 * 否则别人发布新版本时，在途 Run 会中途换策略。
 * （M3 已完成 {@code execution_snapshot.context_policy} 发布期双写：该列供回放 / 审计
 * 固化策略快照，本路径仍只读 {@code cap_agent_def.generationParams}，不引入新读源。）
 *
 * <p>⚠️ <b>能力读取防回落</b>：模型目录按 {@code modelName} <b>精确匹配</b>，
 * 命中不到就按 unknown 处理并让 {@link ContextPolicyResolver} 上调 reserve（R10）。
 * ⛔ 不调用会「回落场景默认模型」的解析方法——拿另一台模型的能力给这台模型算预算，
 * 是「不报错、只是悄悄超窗」的典型失真。
 *
 * <p>⚠️ <b>fail-closed</b>：算法层拒因逐码投影到中性稳定码，兜底异常一律
 * {@code CONTEXT_ASSEMBLY_FAILED}；⛔ 绝不 catch 后返回 legacy。
 */
public final class ContextRequestAssemblyAdapter implements RequestAssemblyPort {

    private final @NonNull GlobalContextPolicy global;
    private final @NonNull ContextAssembler assembler;
    private final @NonNull Summarizer summarizer;
    private final @Nullable CapAgentDefPort agentDefs;
    private final @Nullable ModelCatalogPort catalog;

    /**
     * @param assembler 组合根持有的<b>唯一</b>装配内核 Bean。
     *                  ⛔ adapter 内部不得再 {@code new ContextAssembler(...)}：
     *                  两套内核并存会让「改了 yml 阈值却没生效」变成静默失真。
     */
    public ContextRequestAssemblyAdapter(
            @NonNull GlobalContextPolicy global,
            @NonNull ContextAssembler assembler,
            @NonNull Summarizer summarizer,
            @Nullable CapAgentDefPort agentDefs,
            @Nullable ModelCatalogPort catalog) {
        this.global = Objects.requireNonNull(global, "global 不得为 null");
        this.assembler = Objects.requireNonNull(assembler, "assembler 不得为 null");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不得为 null");
        this.agentDefs = agentDefs;
        this.catalog = catalog;
    }

    @Override
    public @NonNull RequestAssemblyResult assemble(@NonNull RequestAssemblyInput input) {
        Objects.requireNonNull(input, "input 不得为 null");

        // ── [D11] 总熔断：零副作用短路，返回 legacy 同一引用 ──────────────
        if (!global.enabled()) {
            return RequestAssemblyResult.passthrough(input.legacyRequest());
        }

        try {
            if (agentDefs == null) {
                throw new RequestAssemblyException(
                        Reason.CONTEXT_ASSEMBLY_FAILED,
                        "全局压缩已开启但未装配 CapAgentDefPort：无法读取冻结策略");
            }
            Optional<CapAgentDef> definition = agentDefs.find(
                    input.tenantId(), input.agentId(), input.agentVersion());
            if (definition.isEmpty()) {
                throw new RequestAssemblyException(
                        Reason.CONTEXT_ASSEMBLY_FAILED,
                        "冻结版本的 Agent 定义不存在: agent=" + input.agentId()
                                + ", version=" + input.agentVersion());
            }
            ContextPolicy agentPolicy =
                    ContextPolicyReader.read(definition.get().generationParams());
            ModelCapabilities capabilities = capabilitiesOf(input.modelId());
            ContextPolicy effective = ContextPolicyResolver.resolve(new ContextPolicyResolver.Input(
                    agentPolicy, capabilities, global, input.maxOutputTokens(),
                    String.valueOf(input.agentVersion())));

            ContextSpec spec = new ContextSpec(
                    input.tenantId(), input.scopeKey(), input.modelId(),
                    input.stableSystemPrompt(),
                    RequestContextProjection.canonicalToolSchemas(input.toolSchemas()),
                    input.firstUserTurn(), input.memoryBlock(), input.ragBlocks(),
                    input.historyMessages(), input.currentInput(),
                    EffectivePolicyProjection.toEffectivePolicy(effective),
                    capabilities, input.upToSeq(), input.carryOverStateJson(),
                    input.fencingToken());

            AssembledContext out = assembler.assemble(new AssemblyInput(
                    spec, input.legacyRequest(), summarizer, input.priorCompactions()));

            // ⚠️ 行为分流只认引用身份，⛔ 不在这里重写 enabled/strategy 判定。
            boolean assembled = out.request() != input.legacyRequest();
            return new RequestAssemblyResult(
                    out.request(), assembled, out.tier(), out.fallback(),
                    out.beforeTokens(), out.afterTokens(), out.compactionCount(),
                    out.checkpointDelta());
        } catch (RequestAssemblyException e) {
            throw e;
        } catch (ContextPolicyRejectedException e) {
            throw new RequestAssemblyException(
                    reasonOf(e.reason().name()), e.getMessage(), e);
        } catch (ContextBudgetException e) {
            throw new RequestAssemblyException(
                    reasonOf(e.reason().name()), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new RequestAssemblyException(
                    Reason.CONTEXT_ASSEMBLY_FAILED,
                    "上下文统一装配失败: " + e.getMessage(), e);
        }
    }

    /**
     * 模型能力精确读取：只认目录里<b>启用中且模型名逐字相等</b>的那一条。
     * 目录缺失 / 未登记一律 unknown（窗口归零 → Resolver 上调 reserve），绝不回落默认模型。
     */
    private @NonNull ModelCapabilities capabilitiesOf(@NonNull String modelId) {
        if (catalog == null) {
            return ModelCapabilities.unknown(modelId);
        }
        for (ModelDefinition def : catalog.list()) {
            if (def.isEnabled() && def.modelName().equals(modelId)) {
                ModelContextCapabilities c = def.contextCapabilities();
                return new ModelCapabilities(modelId, c.contextWindowTokens(),
                        c.maxInputTokens(), c.tokenizerId(),
                        c.supportsPromptCaching(), c.supportsTokenizeApi());
            }
        }
        return ModelCapabilities.unknown(modelId);
    }

    /** 算法层稳定拒因 → 中性稳定拒因；未知码一律兜底，绝不静默当成功。 */
    private static @NonNull Reason reasonOf(@NonNull String name) {
        try {
            return Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Reason.CONTEXT_ASSEMBLY_FAILED;
        }
    }
}
