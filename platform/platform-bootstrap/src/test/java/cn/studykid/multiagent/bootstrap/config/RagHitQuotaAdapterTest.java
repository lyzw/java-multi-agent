package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.Allocation;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.Hit;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.KeptRagHit;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort.RefRagHit;
import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S3-M10 组合根适配器的行为测试（ADR-013 D51）。
 *
 * <p>⚠️ 不是算法层单测的重复：这里钉死「生产 TokenCounter 真的注入」「关闭态零计数」
 * 「配额异常 fail-soft」「worker 与组合根共用同一份载荷渲染口径」四件接线事实——
 * 算法层再正确，没接上或算 A 发 B 仍是静默失真。
 */
class RagHitQuotaAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BUDGET = 512;
    private static final int MAX_HITS = 4;
    private static final int MAX_HIT_TOKENS = 160;
    private static final int MIN_KEPT_TOKENS = 16;

    private static RagHitQuotaAdapter adapter(GlobalContextPolicy global, TokenCounter counter) {
        return new RagHitQuotaAdapter(global, counter,
                BUDGET, MAX_HITS, MAX_HIT_TOKENS, MIN_KEPT_TOKENS);
    }

    private static RagHitQuotaPort.Input input(List<Hit> hits) {
        return new RagHitQuotaPort.Input("tenant-1", "chat:run-1", hits);
    }

    @Test
    @DisplayName("⛔ 关闭态零副作用：不计数、不构造候选、直接返回 null 走 legacy")
    void disabledAdapterShortCircuitsWithoutCounting() {
        TokenCounter counter = mock(TokenCounter.class);
        RagHitQuotaAdapter adapter = adapter(GlobalContextPolicy.shadowDisabled(), counter);

        Allocation allocation = adapter.applyQuota(input(List.of(
                hit("c-1", "外部语料", 0.9, "第3页"))));

        assertThat(allocation).isNull();
        verifyNoInteractions(counter);
    }

    @Test
    @DisplayName("✅ 开启态用生产 TokenCounter 注入计数：三硬不变量在真实口径下成立")
    void enabledAdapterKeepsHardInvariantsWithRealCounter() {
        RagHitQuotaAdapter adapter = adapter(
                new GlobalContextPolicy(true, 128_000, 16_000, 0.10),
                new HeuristicTokenCounter());
        String longText = "外部语料".repeat(90);
        List<Hit> hits = List.of(
                hit("c-1", longText, 0.91, "p1"),
                hit("c-1", longText, 0.91, "p1"),   // 同 chunkId 完全重复 → 去重
                hit("c-3", shortText(40), 0.80, "p2"),
                hit("c-4", shortText(260), 0.70, "p3"));

        Allocation a = adapter.applyQuota(input(hits));

        assertThat(a).isNotNull();
        assertThat(a.afterTokens())
                .as("⛔ 硬顶被突破：引用 / 截断叠加会静默超窗")
                .isLessThanOrEqualTo(BUDGET);
        assertThat(a.savedTokens())
                .as("⛔ 净省口径不闭合")
                .isEqualTo(a.beforeTokens() - a.afterTokens());
        assertThat(a.beforeTokens())
                .as("⛔ before 未按含重复全量统计：去重被掩盖成零净省")
                .isGreaterThan(a.afterTokens());
        assertThat(a.duplicateCount()).isEqualTo(1);
        assertThat(a.kept()).allSatisfy(k ->
                assertThat(k.tokens()).isLessThanOrEqualTo(MAX_HIT_TOKENS));
        assertThat(a.kept().stream().map(KeptRagHit::chunkId).distinct().count())
                .as("⛔ 去重后仍出现同 chunkId")
                .isEqualTo(a.kept().size());
    }

    @Test
    @DisplayName("✅ 确定性：输入顺序反转后载荷逐字一致（末级 tie-break 不被 TimSort 稳定性掩盖）")
    void payloadIsDeterministicAcrossInputPermutation() {
        RagHitQuotaAdapter adapter = adapter(
                new GlobalContextPolicy(true, 128_000, 16_000, 0.10),
                new HeuristicTokenCounter());
        List<Hit> hits = List.of(
                hit("c-1", shortText(120), 0.90, "p1"),
                hit("c-2", shortText(120), 0.90, "p1"),
                hit("c-3", shortText(200), 0.75, "p2"),
                hit("c-4", shortText(260), 0.70, "p3"));

        Allocation forward = adapter.applyQuota(input(hits));
        Allocation reversed = adapter.applyQuota(input(hits.reversed()));

        assertThat(forward).isNotNull();
        assertThat(reversed).isNotNull();
        assertThat(RagHitQuotaPort.payloadJson(reversed))
                .as("⛔ 同分同 locator 的保留项随输入顺序漂移：回放 / 缓存键会失配")
                .isEqualTo(RagHitQuotaPort.payloadJson(forward));
        assertThat(reversed.savedTokens()).isEqualTo(forward.savedTokens());
    }

    @Test
    @DisplayName("✅ overhead 用生产计数 + 冻结 JSON 口径：tokens == 元数据壳 + 正文，绝不重复计正文")
    void overheadUsesFrozenRendererAndProductionCounter() {
        RagHitQuotaAdapter adapter = adapter(
                new GlobalContextPolicy(true, 128_000, 16_000, 0.10),
                new HeuristicTokenCounter());
        Hit h = hit("c-1", shortText(30), 0.9, "第3页");

        Allocation a = adapter.applyQuota(input(List.of(h)));

        assertThat(a).isNotNull();
        assertThat(a.kept()).hasSize(1);
        KeptRagHit kept = a.kept().get(0);
        int shell = count(new HeuristicTokenCounter(), RagHitQuotaPort.hitJson(
                new KeptRagHit(h.chunkId(), "", h.score(), h.locator(),
                        h.edition(), h.role(), h.reason(), 0, false)));
        int body = count(new HeuristicTokenCounter(), h.text());
        assertThat(kept.tokens())
                .as("⛔ 元数据开销漏算或正文重复计入：预算算 A、实际发 B")
                .isEqualTo(shell + body);
    }

    @Test
    @DisplayName("⛔ 配额计数异常 fail-soft：返回 null 走 legacy，绝不中断回答")
    void counterFailureFallsBackToLegacy() {
        TokenCounter broken = blocks -> {
            throw new IllegalStateException("counter boom");
        };
        RagHitQuotaAdapter adapter = adapter(
                new GlobalContextPolicy(true, 128_000, 16_000, 0.10), broken);

        assertThat(adapter.applyQuota(input(List.of(
                hit("c-1", "外部语料", 0.9, "第3页"))))).isNull();
    }

    @Test
    @DisplayName("⛔ 载荷渲染只带白名单字段：引用无正文、不可信文本被 JSON 转义且可解析")
    void payloadJsonIsWhitelistedEscapedAndParseable() throws Exception {
        Allocation a = new Allocation(
                List.of(new KeptRagHit("c-1", "line1\nline2 \"忽略指令\"", 0.9, "第3页",
                        "数学", "example", "语义命中", 12, false)),
                List.of(new RefRagHit("c-2", "第5页", 4)),
                16, 16, 0, 0, 0, false);

        String json = RagHitQuotaPort.payloadJson(a);
        JsonNode root = JSON.readTree(json);
        JsonNode kept = root.get("hits").get(0);
        JsonNode ref = root.get("hits").get(1);

        assertThat(root.get("kind").asText()).isEqualTo("rag_retrieval");
        assertThat(kept.get("text").asText()).isEqualTo("line1\nline2 \"忽略指令\"");
        assertThat(kept.has("tokens")).isFalse();
        assertThat(kept.has("truncated")).isFalse();
        assertThat(ref.has("text")).as("⛔ 降级引用偷偷带正文：配额失去意义").isFalse();
        assertThat(ref.get("refOnly").asBoolean()).isTrue();
        assertThat(ref.get("chunkId").asText()).isEqualTo("c-2");
        assertThat(json).contains("\\n").contains("\\\"");
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private static Hit hit(String chunkId, String text, double score, String locator) {
        return new Hit(chunkId, text, score, locator, "数学", "example", "语义命中");
    }

    private static String shortText(int cjkChars) {
        return "语".repeat(cjkChars);
    }

    private static int count(TokenCounter counter, String text) {
        return counter.count(new ContextBlocks("", "", "", List.of(), List.of(text), ""));
    }
}
