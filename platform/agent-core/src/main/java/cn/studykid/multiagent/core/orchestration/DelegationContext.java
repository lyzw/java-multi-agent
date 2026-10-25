package cn.studykid.multiagent.core.orchestration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.idempotency.IdempotencyKey;

/**
 * 当前执行中的 Agent 在<b>委派调用树</b>里的位置（多级 Agent v1.1 · 第十五批）。
 *
 * <p>⚠️ <b>存在的唯一理由：让嵌套委派按「当前 Agent」判定，而不是按根 Agent</b>。
 * 此前 {@code DelegationExecutor.resolveParent} 虽已支持「调用方给的父」，
 * 但闸门**恒传 null** → 子 Agent 发起的委派被按<b>根 Agent</b> 的编排声明判定。
 * 后果是<b>越权路径</b>：子 Agent S 能借根 Agent R 的声明，
 * 把工作交给 R 声明过、而 S 自己<b>根本没声明</b>（甚至 S 根本没有委派能力）的 Agent。
 * 这直接违反「委派关系 ≠ 授权关系」：S 的委派拓扑必须来自 S 自己的声明。
 *
 * <p>⚠️ <b>它与 {@code ExecutionScope} 是两件事，不可互相替代</b>：
 * <ul>
 *   <li>{@code ExecutionScope} 回答「<b>按哪个 release 授权</b>」
 *       （子 Agent 不得借用父的闭包 —— 那是权限下放）；</li>
 *   <li>本对象回答「<b>当前处在调用树的哪一层、当前 Agent 是谁</b>」
 *       （委派拓扑、深度、父子链都由此推导）。</li>
 * </ul>
 * 二者同时存在时，Agent 标识与版本<b>必须一致</b> —— 不一致说明上下文串了，
 * 调用方必须 fail-closed（见 {@code ToolInvocationGate} 的一致性校验）。
 *
 * @param agentId      当前执行中的 Agent 标识（对嵌套委派而言即「父」）
 * @param agentVersion 当前 Agent 的<b>固定版本号</b>（不是「当前已发布版本」）
 * @param depth        当前 Agent 所在深度（<b>根 Agent = 1</b>，其直接子 Agent = 2）
 * @param invocationId 当前 Agent 自己对应的委派调用标识；
 *                     <b>根 Agent 为 {@code null}</b>（根调用没有父调用）
 */
public record DelegationContext(
        @NonNull String agentId,
        int agentVersion,
        int depth,
        @Nullable String invocationId) {

    public DelegationContext {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("委派上下文的 agentId 不能为空");
        }
        if (agentId.contains(IdempotencyKey.SEPARATOR)) {
            // ⚠️⚠️ Agent 标识会进入幂等键（第十七批）：键是「run/agent/tool/call」，
            //    成分里含分隔符就无法唯一分解 —— (a/b, c) 与 (a, b/c) 会撞键，
            //    而撞键的后果是「一次工具调用被静默吞掉」。
            //    在**构造期**拒绝：委派上下文在子执行开始前就构造好了，
            //    那时失败还能定位到是哪个 Agent 的标识有问题。
            throw new IllegalArgumentException("委派上下文的 agentId 不得包含分隔符 '"
                    + IdempotencyKey.SEPARATOR + "'，否则幂等键无法唯一分解: " + agentId);
        }
        if (agentVersion <= 0) {
            throw new IllegalArgumentException("委派上下文的 agentVersion 必须为正数，收到: "
                    + agentVersion);
        }
        if (depth <= 0) {
            // ⚠️ 深度必须为正：根 Agent = 1。0 或负数会让「深度上限」判定失去意义
            //    （例如 depth=0 时任何上限都判不出超限）。
            throw new IllegalArgumentException("委派上下文的 depth 必须为正数，收到: " + depth);
        }
    }

    /**
     * 当前执行中的 Agent 引用（幂等键的 Agent 维度 · 第十七批）。
     *
     * <p>⚠️ 格式定义在 {@link IdempotencyKey#agentRefOf}：
     * 本上下文与执行作用域必须渲染出<b>同一个</b>字符串，
     * 否则同一次调用在两条链路上会算出两个不同的键。
     *
     * @return Agent 引用（{@code agentId@version}）
     */
    public @NonNull String agentRef() {
        return IdempotencyKey.agentRefOf(agentId, agentVersion);
    }

    /**
     * @param agentId      当前 Agent 标识
     * @param agentVersion 当前 Agent 的固定版本号
     * @param depth        当前 Agent 所在深度（根 = 1）
     * @return 根 Agent 的委派上下文（无父调用）
     */
    public static @NonNull DelegationContext root(@NonNull String agentId, int agentVersion,
                                                   int depth) {
        return new DelegationContext(agentId, agentVersion, depth, null);
    }
}
