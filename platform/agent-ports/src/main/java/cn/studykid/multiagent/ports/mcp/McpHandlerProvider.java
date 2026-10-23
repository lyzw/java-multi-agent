package cn.studykid.multiagent.ports.mcp;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * MCP-backed 工具处理器的动态解析端口（07 §3「唯一执行入口」 · §6.5）。
 *
 * <p>⚠️ <b>MCP 工具不得有独立的执行入口</b>（ADR-011 / 07 §3）：
 * Native 与 MCP-backed 工具必须都从 {@code ToolInvocationGate} 进入，
 * 否则 Schema 校验、执行时重授权、审批、预算、账本、执行准入、
 * 执行意图、结果落盘这八道闸门对 MCP 工具全部失效。
 *
 * <p>⚠️ <b>为什么是「动态」解析而不是启动期枚举</b>：
 * MCP 工具集来自「已发布的工具定义 + MCP Server 的固定清单」，
 * 而清单会漂移（S5c）。启动期枚举出的处理器集合会立刻变陈旧；
 * 动态解析保证「当前是否可调用」由<b>当下的</b>权威状态决定，
 * 漂移的最终判定仍由 §5.8 的操作级复核承担。
 */
public interface McpHandlerProvider {

    /**
     * 按工具 id 解析 MCP-backed 处理器。
     *
     * @param toolName 工具逻辑 id
     * @return 处理器；该工具不是 MCP 工具、或 MCP 侧不可用时为 {@link Optional#empty()}
     */
    @NonNull Optional<ToolHandler> resolve(@NonNull String toolName);
}
