package cn.studykid.multiagent.ports.http;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 远程 HTTP 工具处理器的动态解析端口（第三十七批 · 设计文档 §13.1）。
 *
 * <p>⚠️ <b>HTTP 工具不得有独立的执行入口</b>（ADR-011 / 07 §3）：
 * 与 MCP-backed 工具一样，HTTP 工具必须从 {@code ToolInvocationGate} 进入，
 * 否则 Schema 校验、执行时重授权、审批、预算、账本、执行准入、
 * 执行意图、结果落盘这八道闸门对它全部失效。
 *
 * <p>⚠️ <b>为什么是「动态」解析</b>：与 MCP 同理 ——
 * Tool 定义会随发布而变化（目标 URL 改版就是新版本），
 * 启动期枚举出的处理器集合会立刻变陈旧。
 */
public interface HttpToolHandlerProvider {

    /**
     * 按工具 id 解析 HTTP-backed 处理器。
     *
     * @param toolName 工具逻辑 id
     * @return 处理器；该工具不是 HTTP 工具、或不可用时为 {@link Optional#empty()}
     */
    @NonNull Optional<ToolHandler> resolve(@NonNull String toolName);
}
