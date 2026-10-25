package cn.studykid.multiagent.core.cap;

import org.jspecify.annotations.NonNull;

/**
 * 执行作用域（多级 Agent v1.1 · FR-025）。
 *
 * <p>⚠️ <b>存在的唯一理由：防止子 Agent 冒用父 Agent 的能力</b>。
 * CAP 授权链路默认按 <b>Run 快照里的根 Agent</b> 解析闭包
 * （{@code CapabilitySnapshot.agentId()}）——这是单 Agent 场景的正确口径，
 * 但在多级场景下会出事：
 * <ul>
 *   <li>子 Agent 的工具不在根闭包里 → 全部被拒（TOOL_NOT_DECLARED），子 Agent 什么都做不了；</li>
 *   <li>更糟的是反向风险：若简单地「沿用根闭包」，子 Agent 就能调用<b>父 Agent 的工具</b> ——
 *       父的权限被下放给子，等于用委派绕过能力分层（Sev-0）。</li>
 * </ul>
 * 故子 Agent 的每一次工具调用都必须带本作用域：
 * <b>按子 Agent 自己的固定 release 解析闭包，并与委派时算出的闭包指纹比对</b>。
 *
 * <p>⚠️ <b>固定版本而非「当前已发布」</b>：委派目标在发布时已固定 release
 * （MA-T02 / MA-T08），子执行必须绑定同一 release，
 * 否则子 Agent 发布新版本后，在途委派会静默漂移到新版本。
 *
 * @param agentId           执行主体（子 Agent）标识
 * @param agentVersion      执行主体的<b>固定版本号</b>
 * @param expectedFingerprint 该版本闭包的指纹（委派时算出，用于逐次比对防漂移）
 */
public record ExecutionScope(
        @NonNull String agentId,
        int agentVersion,
        @NonNull String expectedFingerprint
) {

    public ExecutionScope {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("执行作用域的 agentId 不能为空");
        }
        if (agentVersion <= 0) {
            throw new IllegalArgumentException("执行作用域的 agentVersion 必须为正数，收到: "
                    + agentVersion);
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new IllegalArgumentException("执行作用域的闭包指纹不能为空");
        }
    }
}
