package cn.studykid.multiagent.ports.context;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * 统一装配入口的产出（《方案》§6.2-B，M6 端口契约）。
 *
 * <p>⚠️ <b>三条模型链路共用同一结果类型</b>：Worker / SSE / Console 的装配都返回本对象，
 * 调用方只消费 {@link #request()} 作为唯一送模型的请求，⛔ 绝不二次拼 prompt
 * （否则稳定前缀会被动态追加，[D6] 失效）。
 *
 * <p>⚠️ {@code checkpointDelta} 为 null 表示本轮不产生 checkpoint（W0/W1/W2 或关闭态），
 * ; {@code tier} 是触发档位（0=W0 未压缩，1/2/3 对应 W1/W2/W3）。
 * {@code fallback=true} 表示摘要/校验失败回落 W2，绝不伪造摘要。
 *
 * <p>⚠️ 全参构造唯一，不提供兼容构造器（铁律 #10）。纯值对象，零框架依赖。
 *
 * @param request          唯一送往模型的请求（稳定前缀已固化）
 * @param checkpointDelta  本轮 checkpoint 写入载荷；null = 不写
 * @param tier             触发档位 0/1/2/3
 * @param fallback         是否回退到 W2
 * @param beforeTokens     压缩前全窗口估算
 * @param afterTokens      压缩后全窗口估算
 * @param compactionCount  本 Run 已发生的压缩次数
 */
public record AssembledContext(
        @NonNull ModelRequest request,
        @Nullable CheckpointDelta checkpointDelta,
        int tier,
        boolean fallback,
        int beforeTokens,
        int afterTokens,
        int compactionCount) {

    public AssembledContext {
        if (request == null) {
            throw new IllegalArgumentException("AssembledContext.request 不得为 null（fail-closed）");
        }
        if (tier < 0 || tier > 3) {
            throw new IllegalArgumentException("AssembledContext.tier 必须是 0/1/2/3");
        }
        if (compactionCount < 0) {
            throw new IllegalArgumentException("AssembledContext.compactionCount 不得为负");
        }
        if (beforeTokens < 0 || afterTokens < 0) {
            throw new IllegalArgumentException("AssembledContext token 计数不得为负");
        }
    }
}
