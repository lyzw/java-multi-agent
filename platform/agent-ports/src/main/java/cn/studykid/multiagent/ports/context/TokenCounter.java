package cn.studykid.multiagent.ports.context;

import org.jspecify.annotations.NonNull;

/**
 * 全窗口 Token 计数端口（ADR-013）。
 *
 * <p>⚠️ <b>契约是「全窗口」而不是「消息序列」</b>：实现必须把
 * {@link ContextBlocks} 的六个块全部计入，含工具 Schema 与系统提示词。
 * 只统计消息序列的实现会让预算口径退回现状的静默超窗形态。
 *
 * <p>⚠️ 计数必须是<b>确定性纯函数</b>：同输入恒等输出，不读时钟、
 * 不读租户上下文、不做网络调用 —— 影子对照与缓存键都依赖这一性质。
 */
public interface TokenCounter {

    /**
     * @param blocks 全窗口上下文块
     * @return 估算 token 数（含消息封装开销）
     */
    int count(@NonNull ContextBlocks blocks);
}
