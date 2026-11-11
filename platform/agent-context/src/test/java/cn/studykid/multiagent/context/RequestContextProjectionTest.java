package cn.studykid.multiagent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;

/**
 * {@link RequestContextProjection} 确定性与全窗口口径测试（ADR-013 / M4 / RV-1、RV-2）。
 *
 * <p>⚠️ 确定性不是「 nice-to-have 」：{@code toolSchemas} 是 {@code Map.copyOf}，
 * 迭代序不保证。若投影结果随迭代序变化，同一请求两次估算得到不同 token 数 →
 * 校准样本全是噪声、bias 悄悄失真，且<b>不报错</b>。本类用「两组插入顺序相反的
 * 等价 Map 必须投影出完全相同文本」把这一形态钉死。
 */
class RequestContextProjectionTest {

    private static final HeuristicTokenCounter COUNTER = new HeuristicTokenCounter();

    @Test
    @DisplayName("✅ 同一请求的 toolSchemas 插入顺序不同 → 投影文本与估算完全一致")
    void toolSchemasCanonicalRegardlessOfInsertionOrder() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("z_tool", Map.of("beta", 2, "alpha", 1));
        forward.put("a_tool", List.of("x", "y"));

        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("a_tool", List.of("x", "y"));
        reverse.put("z_tool", Map.of("alpha", 1, "beta", 2));

        ModelRequest a = request(forward);
        ModelRequest b = request(reverse);

        assertThat(RequestContextProjection.project(a))
                .as("⛔ Map.copyOf 迭代序影响了投影：同请求两次估算会得到不同 token 数，"
                        + "校准样本变噪声、bias 静默失真")
                .isEqualTo(RequestContextProjection.project(b));
        assertThat(COUNTER.count(RequestContextProjection.project(a)))
                .isEqualTo(COUNTER.count(RequestContextProjection.project(b)));
    }

    @Test
    @DisplayName("⚠️⚠️ canonical() 直接吞「保序 Map」也必须按 key 排序（撤 TreeMap 必红，杜绝空转）")
    void canonicalSortsRawInsertionOrderedMap() {
        // ⚠️ 挤出压力：ModelRequest 构造器会 Map.copyOf 归一化迭代序，
        //    所以「比两个 request 的投影」在撤掉 TreeMap 后仍可能假绿。
        //    这里绕过 ModelRequest，直接把两个「保序但插入顺序相反」的 Map 喂给 canonical()。
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("z_tool", Map.of("beta", 2, "alpha", 1));
        forward.put("a_tool", List.of("x", "y"));

        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("a_tool", List.of("x", "y"));
        reverse.put("z_tool", Map.of("alpha", 1, "beta", 2));

        String a = RequestContextProjection.canonical(forward);
        String b = RequestContextProjection.canonical(reverse);

        assertThat(a)
                .as("⛔ 撤掉 TreeMap 排序后，同内容不同插入序会产生不同投影文本："
                        + "跨 JVM 重启的迭代序漂移会让校准样本变噪声、bias 静默失真")
                .isEqualTo(b);
        // 正向断言「确实排序了」：a_tool 必须排在 z_tool 之前，而不是仅比较相等。
        assertThat(a.indexOf("a_tool")).as("⛔ 未按 key 字典序排序").isLessThan(a.indexOf("z_tool"));
        assertThat(a.indexOf("alpha")).as("⛔ 嵌套 Map 也未按 key 排序").isLessThan(a.indexOf("beta"));
    }

    @Test
    @DisplayName("✅ 投影覆盖 systemPrompt + 工具 Schema + 全部消息（全窗口，不是消息序列）")
    void projectionCoversAllSixBlockSources() {
        ModelRequest request = new ModelRequest(
                "minimax:MiniMax-M2.5",
                "你是错题归因助手，必须遵守以下约束……",
                "这道题为什么错了？",
                0.3, 2048, java.time.Duration.ofSeconds(30),
                Map.of("grade_homework", Map.of("type", "object", "required", List.of("qid"))),
                List.of(
                        ModelMessage.user("第一问：请分析知识点"),
                        ModelMessage.assistantToolCalls("调用工具", List.of(
                                new ModelMessage.ToolCallEcho("call-1", "grade_homework", "{\"qid\":42}"))),
                        ModelMessage.toolResult("grade_homework", "{\"attribution\":\"概念混淆\"}", "call-1")));

        ContextBlocks blocks = RequestContextProjection.project(request);
        assertThat(blocks.systemPrompt()).contains("错题归因助手");
        assertThat(blocks.toolSchemas()).contains("grade_homework").contains("qid");
        assertThat(blocks.historyTexts()).hasSize(3);
        assertThat(blocks.historyTexts().get(1)).contains("echo=call-1"); // 工具调用回显不丢 token
        assertThat(blocks.historyTexts().get(2)).contains("概念混淆");   // 工具结果不丢 token

        // ⛔ 只按消息序列计数会漏掉 systemPrompt 与 toolSchemas 的真实占用。
        int fullWindow = COUNTER.count(blocks);
        int messagesOnly = COUNTER.count(new ContextBlocks(
                "", "", "", List.of(), blocks.historyTexts(), request.userContent()));
        assertThat(fullWindow)
                .as("全窗口必须严格大于「只看消息」：否则预算退回静默超窗口径")
                .isGreaterThan(messagesOnly);
    }

    @Test
    @DisplayName("✅ 空 messages 的兼容单轮请求也能投影（userContent 作为当前输入）")
    void singleTurnCompatRequestProjects() {
        ModelRequest single = new ModelRequest(
                "minimax:MiniMax-M2.5", "sys", "请解题", 0.3, 1024,
                java.time.Duration.ofSeconds(30), Map.of());
        ContextBlocks blocks = RequestContextProjection.project(single);
        assertThat(blocks.historyTexts()).hasSize(1); // effectiveMessages() 造一条 USER
        assertThat(blocks.currentInput()).isEqualTo("请解题");
        assertThat(COUNTER.count(blocks)).isPositive();
    }

    @Test
    @DisplayName("✅ 确定性压力：20 个等价但键序交替正/逆的请求，计数方差为 0")
    void deterministicUnderRepeatedShuffles() {
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> schemas = new LinkedHashMap<>();
            // 交替正序 / 逆序插入，模拟 Map.copyOf 的不确定迭代序。
            for (int j = 0; j < 8; j++) {
                int k = (i % 2 == 0) ? j : 7 - j;
                schemas.put("tool_" + k, Map.of("p" + k, k, "enabled", k % 2 == 0));
            }
            counts.add(COUNTER.count(RequestContextProjection.project(request(schemas))));
        }
        assertThat(counts).as("⛔ 同内容不同键序产生了不同估算 → 校准失真且静默").containsOnly(counts.get(0));
    }

    private static ModelRequest request(Map<String, Object> schemas) {
        return new ModelRequest("minimax:MiniMax-M2.5", "sys", "user", 0.3, 1024,
                java.time.Duration.ofSeconds(30), schemas);
    }
}
