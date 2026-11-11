package cn.studykid.multiagent.ports.context;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 模型级上下文能力（{@code model_catalog} 五列的运行期投影，ADR-013 / 《方案》§3.2-A）。
 *
 * <p>⚠️ <b>R10 特性探测默认关闭</b>：供应商能力未知时一律按「不支持」处理，
 * 绝不能因为「目录里没填」就假设支持精确 tokenize / 前缀缓存 ——
 * 一旦假设错误，估算会偏小而静默超窗（风险 R2 / R10）。
 * {@link #known()} 为 false 时 {@code ContextPolicyResolver} 会回落全局默认窗口
 * 并上调 {@code reserveRatio}。
 *
 * <p>⚠️ 纯值对象：端口层不认识 JDBC / Spring / 任何具体实现。
 */
public record ModelCapabilities(
        /** 模型目录 id；未登记时用 modelRef 文本，仅参与 policyVersion 派生。 */
        @NonNull String modelId,
        /** 输入 + 输出总窗口；{@code <= 0} 表示未知，必须回落（fail-closed）。 */
        int contextWindowTokens,
        /** 允许的输入 token 硬顶；{@code <= 0} 表示未知，回落全局默认。 */
        int maxInputTokens,
        /** 精确计数器标识；{@code null} = 启发式估算。 */
        @Nullable String tokenizerId,
        /** 供应商是否支持前缀缓存（[D6] 稳定前缀守卫的前提）。 */
        boolean supportsPromptCaching,
        /** 供应商是否提供 {@code /tokenize} 精确计数端点。 */
        boolean supportsTokenizeApi) {

    public ModelCapabilities {
        modelId = modelId == null ? "" : modelId;
    }

    /** 能力全部未知：所有布尔特性为 false，窗口值取 0 触发回落。 */
    public static @NonNull ModelCapabilities unknown(@NonNull String modelId) {
        return new ModelCapabilities(modelId, 0, 0, null, false, false);
    }

    /** 目录里是否给出了可用的窗口事实。 */
    public boolean known() {
        return contextWindowTokens > 0 && maxInputTokens > 0;
    }
}
