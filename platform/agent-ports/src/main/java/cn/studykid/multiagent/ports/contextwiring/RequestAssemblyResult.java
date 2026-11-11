package cn.studykid.multiagent.ports.contextwiring;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.context.CheckpointDelta;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * {@link RequestAssemblyPort} 的中立产出（ADR-013 D38 / M7-b）。
 *
 * <p>⚠️ <b>唯一送模型的请求是 {@link #request()}</b>：调用方⛔ 不得再拼 systemPrompt、
 * 不得再替换 messages（[D6] 稳定前缀会被动态追加，围栏块会被绕过）。
 *
 * <p>⚠️ <b>{@link #assembled()} 是行为分流的唯一判据</b>，由 adapter 用
 * {@code out.request() != input.legacyRequest()} 的<b>引用身份</b>推导，
 * ⛔ 不复制「enabled && strategy!=OFF」判定（那是装配内核的内部语义，
 * 两份判定漂移会让关闭态偷偷发新事件 / 换成本预留口径）。
 * <ul>
 *   <li>{@code false} = 关闭等价桥（全局熔断 / Agent 关闭 / strategy=OFF / 未装配端口）：
 *       request 就是 legacy 同一引用，调用方走 legacy trim 事件与 legacy 成本估算，
 *       与「没有压缩功能」逐位一致（[D11] / G1）；</li>
 *   <li>{@code true} = 统一装配已生效：调用方走 {@code onContextCompacted} 新事件、
 *       用 {@link #afterTokens()} 做全窗口成本预留。</li>
 * </ul>
 *
 * <p>⚠️ <b>checkpoint 落点仅预留</b>：M7 阶段 {@link #checkpointDelta()} 恒为 null，
 * 真实落库属 M11。端口先把字段钉死，避免 S4 接线时又改调用方结构。
 *
 * <p>⚠️ 全参构造唯一（铁律 #10），纯值对象，零框架依赖。
 *
 * @param request          唯一送往模型的请求
 * @param assembled        统一装配是否真正生效（引用身份判定，非字符串判定）
 * @param tier             触发档位 0/1/2/3；关闭态恒 0
 * @param fallback         是否从 W3 摘要失败回落 W2；关闭态恒 false
 * @param beforeTokens     压缩前全窗口估算；关闭态恒 0
 * @param afterTokens      压缩后全窗口估算（成本预留口径）；关闭态恒 0
 * @param compactionCount  本 Run 已发生的压缩次数
 * @param checkpointDelta  本轮 checkpoint 写入载荷；null = 不写（M7 恒 null）
 */
public record RequestAssemblyResult(
        @NonNull ModelRequest request,
        boolean assembled,
        int tier,
        boolean fallback,
        int beforeTokens,
        int afterTokens,
        int compactionCount,
        @Nullable CheckpointDelta checkpointDelta) {

    public RequestAssemblyResult {
        Objects.requireNonNull(request, "RequestAssemblyResult.request 不得为 null");
        if (tier < 0 || tier > 3) {
            throw new IllegalArgumentException("RequestAssemblyResult.tier 必须是 0/1/2/3");
        }
        if (beforeTokens < 0 || afterTokens < 0) {
            throw new IllegalArgumentException("RequestAssemblyResult token 计数不得为负");
        }
        if (compactionCount < 0) {
            throw new IllegalArgumentException("RequestAssemblyResult.compactionCount 不得为负");
        }
        if (!assembled && (tier != 0 || fallback || beforeTokens != 0
                || afterTokens != 0 || checkpointDelta != null)) {
            throw new IllegalArgumentException(
                    "关闭等价态不得携带压缩行为数据（tier/fallback/tokens/checkpoint）——"
                            + "那会让 enabled=false 多报指标 [D11]");
        }
        if (assembled && afterTokens <= 0) {
            throw new IllegalArgumentException(
                    "装配态 afterTokens 必须为正（全窗口估算为 0 意味着预算口径空转）");
        }
    }

    /**
     * 未装配端口 / 关闭等价桥的统一返回：request 就是传入的同一引用，零行为数据。
     */
    public static @NonNull RequestAssemblyResult passthrough(@NonNull ModelRequest legacy) {
        return new RequestAssemblyResult(legacy, false, 0, false, 0, 0, 0, null);
    }

    /** 净减少 token（调用方 clamp 到 ≥0 后交给 Observer，端口层不做隐式推导）。 */
    public int droppedTokens() {
        return Math.max(0, beforeTokens - afterTokens);
    }
}
