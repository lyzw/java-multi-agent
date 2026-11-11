package cn.studykid.multiagent.context;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.context.ContextBlocks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全窗口计数守卫（RV-1 / 《方案》§3.1、§7.4）。
 *
 * <p>⚠️ <b>撤掉修复点必须变红</b>：把 {@code HeuristicTokenCounter#count} 里
 * {@code countText(blocks.toolSchemas())} 一行删掉，
 * {@link #toolSchemasAreCountedInFullWindow()} 立即失败。
 * 断言用足够长的工具 Schema 造出「挤出压力」，不是弱断言。
 */
class HeuristicTokenCounterTest {

    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();

    /** 约 640 个 ASCII 字符 → 远超 100 token，保证判别力。 */
    private static final String LONG_TOOL_SCHEMAS =
            "{\"name\":\"search\",\"parameters\":{\"query\":\"string\",\"limit\":\"integer\"}}"
                    .repeat(8);

    @Test
    @DisplayName("✅ 工具 Schema 计入全窗口预算（撤掉即红 · RV-1）")
    void toolSchemasAreCountedInFullWindow() {
        ContextBlocks withoutTools = new ContextBlocks(
                "system prompt", "", "", List.of(), List.of(), "current input");
        ContextBlocks withTools = new ContextBlocks(
                "system prompt", LONG_TOOL_SCHEMAS, "", List.of(), List.of(), "current input");

        int delta = counter.count(withTools) - counter.count(withoutTools);

        assertThat(delta)
                .as("⛔ toolSchemas 未计入预算：预算口径退回『只算消息序列』，"
                        + "system + tools 的真实占用会静默超窗")
                .isGreaterThan(100);
    }

    @Test
    @DisplayName("✅ 六个块逐块递增，缺一块即失真")
    void everyBlockContributes() {
        int base = counter.count(ContextBlocks.empty());
        assertThat(base).isZero();

        int withSystem = counter.count(new ContextBlocks(
                "You are a tutor.", "", "", List.of(), List.of(), ""));
        int withTools = counter.count(new ContextBlocks(
                "You are a tutor.", "{\"name\":\"x\"}", "", List.of(), List.of(), ""));
        int withMemory = counter.count(new ContextBlocks(
                "You are a tutor.", "{\"name\":\"x\"}", "知识点：一元二次方程",
                List.of(), List.of(), ""));
        int withRag = counter.count(new ContextBlocks(
                "You are a tutor.", "{\"name\":\"x\"}", "知识点：一元二次方程",
                List.of("命中片段一"), List.of(), ""));
        int withHistory = counter.count(new ContextBlocks(
                "You are a tutor.", "{\"name\":\"x\"}", "知识点：一元二次方程",
                List.of("命中片段一"), List.of("上一轮回答"), ""));
        int withCurrent = counter.count(new ContextBlocks(
                "You are a tutor.", "{\"name\":\"x\"}", "知识点：一元二次方程",
                List.of("命中片段一"), List.of("上一轮回答"), "这道题怎么做"));

        assertThat(withSystem).isGreaterThan(base);
        assertThat(withTools).isGreaterThan(withSystem);
        assertThat(withMemory).isGreaterThan(withTools);
        assertThat(withRag).isGreaterThan(withMemory);
        assertThat(withHistory).isGreaterThan(withRag);
        assertThat(withCurrent).isGreaterThan(withHistory);
    }

    @Test
    @DisplayName("✅ 确定性纯函数 + 中文按字计（不是按 3 字符/token）")
    void deterministicAndCjkAware() {
        String cjk = "这是一道关于一元二次方程的错题解析".repeat(10);
        ContextBlocks blocks = new ContextBlocks("", "", "", List.of(), List.of(cjk), "");

        int first = counter.count(blocks);
        int second = counter.count(blocks);

        assertThat(first).isEqualTo(second);
        assertThat(first)
                .as("中文按字计：~170 字应远大于按英文口径的 ~57 token")
                .isGreaterThan(150);
    }
}
