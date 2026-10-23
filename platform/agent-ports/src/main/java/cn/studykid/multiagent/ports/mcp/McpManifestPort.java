package cn.studykid.multiagent.ports.mcp;

import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * MCP 固定清单端口（§4.4 {@code ManifestOps}）。
 *
 * <p>{@code ManifestOps} = 「当前已发布、经校验的固定 MCP 工具清单」。
 * 它是 §4.4 交集运算的<b>基准集合</b>：
 * <pre>
 *   Server.exposedToolAllowlist = null → ServerAllowedOps := ManifestOps
 *   Server.exposedToolAllowlist = []   → ServerAllowedOps := ∅
 *   Server.exposedToolAllowlist = L   → ServerAllowedOps := L ∩ ManifestOps
 * </pre>
 *
 * <p>⚠️ <b>必须区分「清单为空」与「清单不可用」</b>：
 * 前者是「这个 server 确实没有暴露任何工具」，后者是「读不到这个 server」。
 * 把两者混为一谈，会让「MCP server 宕机」被读成「该 server 无工具可调用」——
 * 前者是<b>运行故障</b>，后者是<b>配置事实</b>，处置方式完全不同。
 * 本端口用 {@link ManifestSnapshot#available()} 区分二者，调用方据此选择原因码。
 *
 * <p>⚠️ <b>本端口不得返回「未采集到就当作全部允许」</b>：
 * 缺配置 ≠ 宽松配置。未采集到清单必须按「不可用」处理（fail-closed）。
 */
public interface McpManifestPort {

    /**
     * 读取某 MCP Server 当前的固定工具清单。
     *
     * @param serverId MCP Server 逻辑 id
     * @return 当前已发布的操作清单；无清单或未接入时为<b>空集</b>（不得返回 {@code null}）
     */
    default @NonNull Set<String> currentManifestOps(@NonNull String serverId) {
        return manifest(serverId).ops();
    }

    /**
     * 读取某 MCP Server 当前的固定工具清单（带可用性标记）。
     *
     * <p>⚠️ <b>实现必须永不抛异常</b>：
     * 清单采集失败（传输故障 / SSRF 拦截 / 熔断打开 / 超时）必须以
     * {@link ManifestSnapshot#available()} = {@code false} 的形式返回。
     * 抛异常会让调用方的异常处理路径与「授权拒绝」路径分叉 ——
     * 而授权链上的任何异常都必须落到一个明确的 reasonCode 上。
     *
     * @param serverId MCP Server 逻辑 id
     * @return 清单快照（可用时 ops 非空且有明确语义；不可用时 ops 为空且带失败原因）
     */
    @NonNull ManifestSnapshot manifest(@NonNull String serverId);

    /**
     * 一次清单读取的结果。
     *
     * @param available 是否成功采集到清单
     * @param ops       操作名集合；不可用时为空集
     * @param failure   失败原因（仅用于日志与排障，<b>不得</b>含凭据材料）
     */
    record ManifestSnapshot(@NonNull Boolean available,
                            @NonNull Set<String> ops,
                            @Nullable String failure) {

        public ManifestSnapshot {
            if (available == null) {
                throw new IllegalArgumentException("available 不能为空");
            }
            if (ops == null) {
                throw new IllegalArgumentException("ops 不能为空");
            }
            ops = Set.copyOf(ops);
        }

        /**
         * @param ops 可用的清单
         * @return 可用快照
         */
        public static @NonNull ManifestSnapshot available(@NonNull Set<String> ops) {
            return new ManifestSnapshot(Boolean.TRUE, ops, null);
        }

        /**
         * @param failure 失败原因
         * @return 不可用快照
         */
        public static @NonNull ManifestSnapshot unavailable(@NonNull String failure) {
            return new ManifestSnapshot(Boolean.FALSE, Set.of(), failure);
        }
    }
}
