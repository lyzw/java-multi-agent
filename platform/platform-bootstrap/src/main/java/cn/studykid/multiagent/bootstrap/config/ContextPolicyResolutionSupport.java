package cn.studykid.multiagent.bootstrap.config;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.application.cap.ContextPolicyReader;
import cn.studykid.multiagent.context.ContextPolicy;
import cn.studykid.multiagent.context.ContextPolicyResolver;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.ports.context.ModelCapabilities;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyException.Reason;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * 组合根内<b>唯一的冻结策略裁决支持</b>（ADR-013 D17 / D49 / G1 A.5）。
 *
 * <p>⚠️ M7 的统一请求装配与 S3-M8-a 的请求侧指纹校验都必须得到<b>同一个</b>
 * {@code policyVersion}。两处各写一遍「读定义 → 读能力 → 调裁决」会随配置演进漂移：
 * 装配按 A 版策略裁剪，校验按 B 版指纹放行旧状态（或反过来把合法状态全丢），
 * 两者都不报错，只是数字悄悄错。故把这段接线收敛到本支持类，
 * 算法仍只由 {@link ContextPolicyResolver} 决定，本类零算法实现。
 *
 * <p>⚠️ 冻结来源：按调用方给出的 {@code tenantId + agentId + agentVersion}
 * 精确读 {@code cap_agent_def.generationParams}；⛔ 不读「当前已发布版本」、
 * 也不读发布快照列当第二真相（D4 / D48）。
 *
 * <p>⚠️ 模型能力精确匹配：只认目录里<b>启用中且模型名逐字相等</b>的那一条；
 * 目录缺失 / 未登记一律 unknown，让裁决内核上调 reserve，⛔ 绝不拿别的模型窗口算预算。
 */
final class ContextPolicyResolutionSupport {

    private ContextPolicyResolutionSupport() {
    }

    /** 同一次裁决的不可拆产物：有效策略 + 本次精确读取的模型能力。 */
    record Resolved(
            @NonNull ContextPolicy policy,
            @NonNull ModelCapabilities capabilities) {
    }

    static @NonNull Resolved resolve(
            @NonNull GlobalContextPolicy global,
            @Nullable CapAgentDefPort agentDefs,
            @Nullable ModelCatalogPort catalog,
            @NonNull String tenantId,
            @NonNull String agentId,
            int agentVersion,
            @NonNull String modelId,
            int maxOutputTokens) {
        Objects.requireNonNull(global, "global 不得为 null");
        if (agentDefs == null) {
            throw new RequestAssemblyException(
                    Reason.CONTEXT_ASSEMBLY_FAILED,
                    "全局压缩已开启但未装配 CapAgentDefPort：无法读取冻结策略");
        }
        Optional<CapAgentDef> definition = agentDefs.find(tenantId, agentId, agentVersion);
        if (definition.isEmpty()) {
            throw new RequestAssemblyException(
                    Reason.CONTEXT_ASSEMBLY_FAILED,
                    "冻结版本的 Agent 定义不存在: agent=" + agentId + ", version=" + agentVersion);
        }
        ContextPolicy agentPolicy =
                ContextPolicyReader.read(definition.get().generationParams());
        ModelCapabilities capabilities = capabilitiesOf(catalog, modelId);
        ContextPolicy effective = ContextPolicyResolver.resolve(new ContextPolicyResolver.Input(
                agentPolicy, capabilities, global, maxOutputTokens,
                String.valueOf(agentVersion)));
        return new Resolved(effective, capabilities);
    }

    static @NonNull ModelCapabilities capabilitiesOf(@Nullable ModelCatalogPort catalog,
                                                      @NonNull String modelId) {
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
}
