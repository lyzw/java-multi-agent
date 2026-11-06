package cn.studykid.multiagent.application.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.core.memory.MemoryEntry;
import cn.studykid.multiagent.ports.persistence.MemoryPort;

/**
 * 跨 Run 记忆召回（P1）。
 *
 * <p><b>职责边界</b>：本类只做"取回 + 围栏 + 记账"，<b>不做</b>
 * 强度计算（属 {@code MemoryStrength}）、不做阈值判定（属配置），
 * 也不决定"要不要召回"（属调用方 {@code RunExecutionLoop}）。
 *
 * <p><b>⚠️ 三条硬约束，破一条就会有一个 Run 级故障</b>：
 * <ol>
 *   <li><b>绝不抛异常</b>：记忆是增强，失败降级为"这次没有记忆"。
 *       若让它抛出去，一次 DB 抖动就会把所有 Run 打成 FAILED。</li>
 *   <li><b>必须走 {@link PromptGuard#sanitize} + 围栏</b>：记忆内容源自
 *       {@code weakness_record.knowledge_point}，而那一列的值是
 *       <b>模型在历史 Run 中生成的</b>。换言之记忆是"模型过去说过的话被存成事实"——
 *       不围栏就等于让历史模型输出直接进可信通道。</li>
 *   <li><b>token 记账必须包含围栏本身</b>：只算正文会让调用方按错误的
 *       数字扣减预算，实际注入的比扣掉的多。</li>
 * </ol>
 */
public class MemoryRecallService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallService.class);

    private final MemoryPort memoryPort;
    private final PromptGuard promptGuard;
    private final int maxItems;
    private final int maxTokens;
    private final double minStrength;
    private final Clock clock;

    public MemoryRecallService(@NonNull MemoryPort memoryPort,
                               @NonNull PromptGuard promptGuard,
                               int maxItems,
                               int maxTokens,
                               double minStrength,
                               @NonNull Clock clock) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("召回条数上限必须为正数，收到: " + maxItems);
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("召回 token 上限必须为正数，收到: " + maxTokens);
        }
        this.memoryPort = memoryPort;
        this.promptGuard = promptGuard;
        this.maxItems = maxItems;
        this.maxTokens = maxTokens;
        this.minStrength = minStrength;
        this.clock = clock;
    }

    /**
     * 召回并渲染为可注入系统提示的记忆块。
     *
     * @param tenantId  租户
     * @param studentId 学生标识；{@code null}/空白 = <b>不召回</b>
     *                  （输入里没有学生标识，就没有可召回的主体）
     * @return 渲染结果；无记忆时 {@link Recalled#empty()}
     */
    public @NonNull Recalled recall(@NonNull String tenantId, @Nullable String studentId) {
        if (studentId == null || studentId.isBlank()) {
            // ⚠️ 不是错误：不是每个 Run 都关联学生（如通用问答 Agent）。
            return Recalled.empty();
        }

        Instant now = clock.instant();
        List<MemoryEntry> entries;
        try {
            entries = memoryPort.recall(tenantId, studentId, maxItems, maxTokens);
        } catch (RuntimeException e) {
            // ⚠️ 硬约束①：降级不穿透。
            log.warn("记忆召回失败，本次 Run 无历史记忆: tenant={}, student={}",
                    tenantId, studentId, e);
            return Recalled.empty();
        }
        if (entries.isEmpty()) {
            return Recalled.empty();
        }

        // ⚠️ 二次过滤：MemoryPort 契约要求已按实时强度筛选，但召回门槛
        //    是**可配置**的（默认 0.40 与领域下限同值）。若运维把它上调到
        //    0.60，端口层仍按 0.40 返回 —— 不在这里再过一遍就等于配置不生效。
        List<MemoryEntry> kept = entries.stream()
                .filter(e -> e.liveStrength(now) >= minStrength)
                .limit(maxItems)
                .toList();
        if (kept.isEmpty()) {
            return Recalled.empty();
        }

        String block = render(kept, now);
        int tokens = MemoryEntry.estimateTokens(block);
        return new Recalled(block, tokens, kept.size());
    }

    /**
     * 渲染记忆块（围栏 + 前言 + 逐条）。
     *
     * <p>⚠️ 每条都过 {@link PromptGuard#sanitize}：知识点的值来自模型历史输出，
     * 可能含围栏字面量、控制字符或零宽字符。
     */
    private String render(List<MemoryEntry> entries, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptGuard.MEMORY_OPEN)
                .append(" count=\"").append(entries.size()).append("\">\n")
                .append(PromptGuard.MEMORY_PREAMBLE).append('\n');
        for (MemoryEntry entry : entries) {
            sb.append("- ").append(promptGuard.sanitize(entry.renderLine(now))).append('\n');
        }
        sb.append(PromptGuard.MEMORY_CLOSE);
        return sb.toString();
    }

    /**
     * 召回结果。
     *
     * @param block  可注入系统提示的文本；无记忆时为 {@code null}
     * @param tokens 该块占用的估算 token（含围栏与前言）；无记忆时为 0
     * @param items  实际注入的条数
     */
    public record Recalled(@Nullable String block, int tokens, int items) {

        public Recalled {
            if (tokens < 0) {
                throw new IllegalArgumentException("token 记账不可为负: " + tokens);
            }
            if (items < 0) {
                throw new IllegalArgumentException("条数不可为负: " + items);
            }
        }

        /** 无记忆。 */
        public static Recalled empty() {
            return new Recalled(null, 0, 0);
        }

        /** 是否有可注入的记忆。 */
        public boolean hasMemory() {
            return block != null && !block.isBlank();
        }
    }
}
