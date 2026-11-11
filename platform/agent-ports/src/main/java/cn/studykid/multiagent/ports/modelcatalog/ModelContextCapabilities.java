package cn.studykid.multiagent.ports.modelcatalog;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 模型级上下文能力（{@code model_catalog} 五列的运行期唯一载体，ADR-013 / §3.2-A）。
 *
 * <p>⚠️ <b>为什么是值对象而不是五个散字段</b>：{@link ModelDefinition} 在
 * 全仓有 11 个全参构造点；把能力收敛为一个组件后，日后新增第六项能力只需改
 * 本 record 与其读写点，而不会在「复制 / 投影 / 回填」里漏带某个字段
 * （铁律 #10 的静默清空事故形态）。本 record 同样<b>不提供兼容构造器</b>。
 *
 * <p>⚠️ <b>R10 特性探测默认关闭</b>：{@link #defaults()} 里两个特性位恒为
 * {@code false}、tokenizer 为 {@code null}。绝不因为「目录里没填」就假设供应商
 * 支持精确 tokenize 或前缀缓存 —— 假设错误会让估算偏小而静默超窗（R2/R10）。
 * 事实探测结果由 IN-6 写入目录后才可显式置 true。
 *
 * <p>⚠️ <b>配置期校验，拒绝悄悄夹紧</b>：{@code maxInputTokens} 必须
 * {@code ≤ contextWindowTokens − 4096}（输出预留）。若运行期自动 clamp，
 * 运维会以为配置生效了、实际跑的是另一个值。
 */
public record ModelContextCapabilities(
        /** 模型总窗口（输入 + 输出）；默认 32768，范围 4096–1000000。 */
        int contextWindowTokens,
        /** 允许的输入 token 硬顶；默认 = 总窗口 − 4096。 */
        int maxInputTokens,
        /** 精确计数器标识；{@code null} = 启发式估算。 */
        @Nullable String tokenizerId,
        /** 供应商是否支持前缀缓存（[D6] 稳定前缀守卫的前提）。 */
        boolean supportsPromptCaching,
        /** 供应商是否提供 {@code /tokenize} 精确计数端点。 */
        boolean supportsTokenizeApi) {

    /** 《方案》§3.2-A 的默认值（能力未知时的一档保守回落）。 */
    public static @NonNull ModelContextCapabilities defaults() {
        return new ModelContextCapabilities(32_768, 28_672, null, false, false);
    }

    public ModelContextCapabilities {
        if (contextWindowTokens < 4_096 || contextWindowTokens > 1_000_000) {
            throw new IllegalArgumentException(
                    "contextWindowTokens 取值范围 [4096, 1000000]，实际为 " + contextWindowTokens);
        }
        if (maxInputTokens < 1_024 || maxInputTokens > 1_000_000) {
            throw new IllegalArgumentException(
                    "maxInputTokens 取值范围 [1024, 1000000]，实际为 " + maxInputTokens);
        }
        if (maxInputTokens > contextWindowTokens - 4_096) {
            throw new IllegalArgumentException(
                    "maxInputTokens 必须 ≤ contextWindowTokens − 4096（输出预留）："
                            + maxInputTokens + " > " + (contextWindowTokens - 4_096));
        }
        if (tokenizerId != null && tokenizerId.length() > 128) {
            throw new IllegalArgumentException("tokenizerId 长度不得超过 128");
        }
    }
}
