package cn.studykid.multiagent.context;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * {@link ContextLayout} 的渲染产物（《方案》§4.2 步骤⑥ / §6.2-B，M5-b）。
 *
 * <p>⚠️ <b>不是 {@code ModelRequest}</b>：M5 阶段零接线，本对象只是算法输出，
 * 供 M7 装配层转成真正的 {@code ModelRequest}。当前新增它<b>不改变任何在途请求字节</b>（G1）。
 *
 * <p>全参构造是唯一构造，不提供 with（铁律 #11）。
 *
 * @param systemPrompt 稳定前缀，逐字等于输入的 systemPrompt（守卫锁死）
 * @param toolSchemas  工具定义，逐字等于输入
 * @param messages     最终消息序列（ANCHOR → 围栏块 → 近期轮 → 当前输入）
 */
public record RenderedContext(@NonNull String systemPrompt,
                              @NonNull String toolSchemas,
                              @NonNull List<ModelMessage> messages) {
    public RenderedContext {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        toolSchemas = toolSchemas == null ? "" : toolSchemas;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
