package cn.studykid.multiagent.bootstrap.config;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * L2 SSE 请求侧策略指纹裁决端口的<b>唯一组合根适配实现</b>（ADR-013 D49 / S3-M8-a）。
 *
 * <p>⚠️ <b>只做接线，不造第二套策略真相</b>：冻结定义读取、模型能力读取与最终裁决
 * 全部委托 {@link ContextPolicyResolutionSupport}，与 M7 统一装配链路共用同一段接线。
 * 本类不缓存、不猜、不把信封里的版本当当前版本。
 *
 * <p>⚠️ <b>关闭等价</b>：全局总开关关闭时第一行就返回 {@code null}，零定义读取 / 零能力读取；
 * 旧客户端不携带压缩态时编排器根本不调用本端口，故影子期请求字节与副作用逐位不变 [D11]。
 *
 * <p>⚠️ <b>请求侧 fail-soft</b>：任何裁决异常都返回 {@code null}，由校验器把客户端状态
 * 静默丢弃并退回全量历史；⛔ 绝不把配置错误变成 HTTP 错误帧，也绝不静默接受旧状态。
 */
public final class ContextPolicyVersionAdapter implements ContextPolicyVersionPort {

    private final @NonNull GlobalContextPolicy global;
    private final @Nullable CapAgentDefPort agentDefs;
    private final @Nullable ModelCatalogPort catalog;

    public ContextPolicyVersionAdapter(
            @NonNull GlobalContextPolicy global,
            @Nullable CapAgentDefPort agentDefs,
            @Nullable ModelCatalogPort catalog) {
        this.global = Objects.requireNonNull(global, "global 不得为 null");
        this.agentDefs = agentDefs;
        this.catalog = catalog;
    }

    @Override
    public @Nullable String resolve(@NonNull Input input) {
        Objects.requireNonNull(input, "ContextPolicyVersionPort.Input 不得为空");
        if (!global.enabled()) {
            return null;
        }
        try {
            ContextPolicyResolutionSupport.Resolved resolved =
                    ContextPolicyResolutionSupport.resolve(
                            global, agentDefs, catalog,
                            input.tenantId(), input.agentId(), input.agentVersion(),
                            input.modelId(), input.maxOutputTokens());
            String version = resolved.policy().policyVersion();
            return version == null || version.isBlank() ? null : version;
        } catch (RuntimeException e) {
            // 请求侧压缩态是「可选增强」：裁决不可用就当没有压缩态，
            // 但绝不在日志里打印客户端原始状态串（它可能含上一轮语义摘要）。
            return null;
        }
    }
}
