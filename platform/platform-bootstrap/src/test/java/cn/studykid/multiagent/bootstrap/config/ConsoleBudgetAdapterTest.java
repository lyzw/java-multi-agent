package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.context.WaterLevel.Thresholds;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetException;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetException.Reason;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort.BudgetInput;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort.BudgetResult;
import cn.studykid.multiagent.ports.context.ContextBlocks;
import cn.studykid.multiagent.ports.context.TokenCounter;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelContextCapabilities;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;

/**
 * S3-M9 L3 ConsoleChat 预算保护 adapter 行为测试（ADR-013 D52）。
 *
 * <p>⚠️ 本类从 {@link ConsoleBudgetPort#protect} 唯一入口进，不测私有方法。
 * 证据分三层：
 * <ol>
 *   <li>关闭态同一引用 + 零能力 / 零计数 / 零摘要；</li>
 *   <li>开启态只切上下文字段，稳定前缀逐字保留，末条 USER 提升为 currentInput 后不重复；</li>
 *   <li>挤出压力下 W1/W2 真省 token、W3 水位零摘要，失败一律 fail-closed。</li>
 * </ol>
 *
 * <p>⛔ 不引用被测实现自己的常量作为断言真值；档位 / token 趋势 / 稳定码均用字面量。
 */
class ConsoleBudgetAdapterTest {

    private static final String MODEL = "model-m9-console";
    private static final String STABLE = "你是控制台助手，请遵守以下约束";
    private static final String CURRENT = "本轮唯一当前输入";

    private final StubCatalog catalog = new StubCatalog();
    private final CountingSummarizer summarizer = new CountingSummarizer();

    @Test
    @DisplayName("✅ 总开关关闭：返回 legacy 同一引用，零能力读取 / 零计数 / 零摘要 / 零行为数据")
    void disabledShortCircuitsWithZeroInteractions() {
        // ⚠️ 让计数器与摘要器「被调用即抛」：关闭态一旦偷跑预算或 W3 会立即爆红。
        ConsoleBudgetPort adapter = adapter(false, new ExplodingCounter());
        ModelRequest legacy = legacy(List.of(
                ModelMessage.user("原始任务"),
                ModelMessage.assistant("历史回答"),
                ModelMessage.user(CURRENT)));
        catalog.failOnRead = true;

        BudgetResult out = adapter.protect(input(legacy, 512));

        assertThat(out.request()).as("⛔ 关闭态换了引用：[D11] 逐位等价失去构造级保证")
                .isSameAs(legacy);
        assertThat(out.budgeted()).isFalse();
        assertThat(out.beforeTokens()).isZero();
        assertThat(out.afterTokens()).isZero();
        assertThat(out.tier()).isZero();
        assertThat(catalog.reads).as("⛔ 关闭态读取了模型能力").isZero();
        assertThat(summarizer.calls.get()).as("⛔ 关闭态调用了摘要器").isZero();
    }

    @Test
    @DisplayName("✅ 开启态 W0：稳定前缀 / 工具 / 非上下文字段逐字保留，末条 USER 只发一次")
    void enabledW0KeepsStablePrefixAndCopiesNonContextFields() {
        catalog.definitions = List.of(definition(MODEL, 32_768, 28_672));
        ConsoleBudgetPort adapter = adapter(true, new HeuristicTokenCounter());
        ModelRequest legacy = legacy(List.of(
                ModelMessage.user("原始任务"),
                ModelMessage.assistant("历史回答"),
                ModelMessage.user(CURRENT)));

        BudgetResult out = adapter.protect(input(legacy, 512));

        assertThat(out.budgeted()).as("⛔ 开启态未生成独立装配请求，adapter 空转").isTrue();
        assertThat(out.tier()).isZero();
        assertThat(out.beforeTokens()).isPositive();
        assertThat(out.afterTokens()).isEqualTo(out.beforeTokens());

        ModelRequest req = out.request();
        assertThat(req).isNotSameAs(legacy);
        assertThat(req.modelId()).isEqualTo(legacy.modelId());
        assertThat(req.userContent()).isEqualTo(legacy.userContent());
        assertThat(req.temperature()).isEqualTo(legacy.temperature());
        assertThat(req.maxTokens()).isEqualTo(legacy.maxTokens());
        assertThat(req.timeout()).isEqualTo(legacy.timeout());
        assertThat(req.toolSchemas()).isEqualTo(legacy.toolSchemas());
        assertThat(req.systemPrompt()).as("⛔ 稳定前缀被追加了 notice / 摘要 / 记忆")
                .isEqualTo(STABLE)
                .doesNotContain("裁剪", "摘要", "记忆", "[trim_notice]");
        assertThat(req.messages()).as("⛔ 末条 USER 进 history 又进 currentInput，重复发送")
                .isEqualTo(legacy.messages());
        assertThat(count(req.messages(), CURRENT)).isEqualTo(1);
        assertThat(summarizer.calls.get()).as("⛔ W0 也调用了摘要器").isZero();
        assertThat(catalog.reads).as("⛔ 未精确读取模型能力").isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 末条非 USER 时不注入空 currentInput，历史全序原样进入渲染")
    void nonUserTailDoesNotInjectEmptyCurrentInput() {
        catalog.definitions = List.of(definition(MODEL, 32_768, 28_672));
        ConsoleBudgetPort adapter = adapter(true, new HeuristicTokenCounter());
        List<ModelMessage> messages = List.of(
                ModelMessage.user("原始任务"),
                ModelMessage.assistant("最后一条不是 USER"));
        ModelRequest legacy = legacy(messages);

        BudgetResult out = adapter.protect(input(legacy, 512));

        assertThat(out.request().messages()).isEqualTo(messages);
        assertThat(out.request().messages()).isNotEmpty();
        assertThat(out.request().messages().get(out.request().messages().size() - 1).content())
                .isEqualTo("最后一条不是 USER");
        assertThat(out.tier()).isZero();
        assertThat(summarizer.calls.get()).isZero();
    }

    @Test
    @DisplayName("✅ 挤出压力：W2 真省 token，W3 水位仍零摘要，notice 只进围栏 USER 消息")
    void pressureRunsW1W2AndNeverW3() {
        catalog.definitions = List.of(definition(MODEL, 8_192, 4_096));
        ConsoleBudgetPort adapter = adapter(true, new HeuristicTokenCounter());
        ModelRequest legacy = legacy(pressuredHistory());

        BudgetResult out = adapter.protect(input(legacy, 512));

        assertThat(out.budgeted()).isTrue();
        assertThat(out.tier()).as("⛔ L3 产出 W3/HARD 档位，D49④ 被破坏")
                .isBetween(1, 2);
        assertThat(out.beforeTokens()).as("⛔ 未造出挤出压力，测试可能空转")
                .isGreaterThan(1_000);
        assertThat(out.afterTokens()).as("⛔ 裁剪后没有净省 token：功能空转")
                .isLessThan(out.beforeTokens())
                .isPositive();
        assertThat(summarizer.calls.get()).as("⛔ TRIM_ONLY 竟然触发了 W3 摘要").isZero();

        ModelRequest req = out.request();
        assertThat(req.systemPrompt()).as("⛔ 稳定前缀被动态内容污染")
                .isEqualTo(STABLE)
                .doesNotContain("上下文预算裁剪", "[summary]", "[memory]");
        assertThat(req.messages()).anySatisfy(m -> assertThat(m.content())
                .as("⛔ W2 notice 未走不可信围栏")
                .contains("<compacted_context trust=\"untrusted\">")
                .contains("[trim_notice]"));
        assertThat(req.messages()).noneSatisfy(m -> assertThat(m.content())
                .as("⛔ 出现 W3 摘要围栏")
                .contains("[summary]"));
        assertThat(req.messages()).as("⛔ 最早可丢轮未被挤出")
                .noneMatch(m -> m.content().contains("EARLY-TOKEN"));
        assertThat(count(req.messages(), CURRENT)).as("⛔ 当前输入被裁或重复").isEqualTo(1);
        assertThat(req.messages().get(req.messages().size() - 1).content())
                .as("⛔ 当前输入必须保持末条且唯一")
                .isEqualTo(CURRENT);
    }

    @Test
    @DisplayName("⛔ 模型能力按 modelName 精确匹配：绝不借另一台模型的窗口算预算")
    void capabilitiesNeverBorrowAnotherModel() {
        catalog.definitions = List.of(definition("other-model", 1_000_000, 995_904));
        ConsoleBudgetPort adapter = adapter(true, new HeuristicTokenCounter());

        // unknown 能力 → totalWindow = hardCeiling + 4096 = 132096；
        // 16000 + 200000 远超它。若错用 other-model 的 100 万窗口就不会拒。
        assertThatThrownBy(() -> adapter.protect(input(legacy(List.of(ModelMessage.user(CURRENT))),
                200_000)))
                .isInstanceOf(ConsoleBudgetException.class)
                .extracting(e -> ((ConsoleBudgetException) e).reason().name())
                .isEqualTo("CONTEXT_WINDOW_EXCEEDED");
    }

    @Test
    @DisplayName("✅ 输入非法只在启用态 fail-closed；关闭态仍逐位等价（D11 不被校验破坏）")
    void invalidOutputTokensFailsClosedOnlyWhenEnabled() {
        ModelRequest legacy = legacy(List.of(ModelMessage.user(CURRENT)));
        BudgetInput invalid = input(legacy, 0);

        ConsoleBudgetPort disabled = adapter(false, new ExplodingCounter());
        assertThat(disabled.protect(invalid).request()).isSameAs(legacy);

        catalog.definitions = List.of(definition(MODEL, 32_768, 28_672));
        ConsoleBudgetPort enabled = adapter(true, new HeuristicTokenCounter());
        assertThatThrownBy(() -> enabled.protect(invalid))
                .isInstanceOf(ConsoleBudgetException.class)
                .extracting(e -> ((ConsoleBudgetException) e).reason())
                .isEqualTo(Reason.CONSOLE_BUDGET_INPUT_INVALID);
    }

    @Test
    @DisplayName("⛔ 预算装配异常包装为 CONSOLE_BUDGET_FAILED，绝不静默回退 legacy")
    void assemblyFailureIsFailClosed() {
        catalog.definitions = List.of(definition(MODEL, 32_768, 28_672));
        // ⚠️ 用 RuntimeException 模拟装配内核故障；AssertionError 是测试夹具失败，
        //    生产 fail-closed 不应（也不必）吞掉 Error。
        ConsoleBudgetPort adapter = adapter(true, new FailingCounter());
        ModelRequest legacy = legacy(List.of(ModelMessage.user(CURRENT)));

        assertThatThrownBy(() -> adapter.protect(input(legacy, 512)))
                .isInstanceOf(ConsoleBudgetException.class)
                .extracting(e -> ((ConsoleBudgetException) e).reason().name())
                .isEqualTo("CONSOLE_BUDGET_FAILED");
    }

    @Test
    @DisplayName("✅ 单例 adapter 无跨请求状态：A 请求痕迹不泄漏到 B 请求")
    void noCrossRequestState() {
        catalog.definitions = List.of(definition(MODEL, 32_768, 28_672));
        ConsoleBudgetPort adapter = adapter(true, new HeuristicTokenCounter());

        BudgetResult a = adapter.protect(input(legacy(List.of(
                ModelMessage.user("REQ-A-UNIQUE"),
                ModelMessage.assistant("REQ-A-ASSISTANT"),
                ModelMessage.user(CURRENT))), 512));
        BudgetResult b = adapter.protect(input(legacy(List.of(
                ModelMessage.user("REQ-B-UNIQUE"),
                ModelMessage.assistant("REQ-B-ASSISTANT"),
                ModelMessage.user(CURRENT))), 512));

        String aText = texts(a.request());
        String bText = texts(b.request());
        assertThat(aText).contains("REQ-A-UNIQUE").doesNotContain("REQ-B-UNIQUE");
        assertThat(bText).contains("REQ-B-UNIQUE").doesNotContain("REQ-A-UNIQUE");
        assertThat(a.beforeTokens()).isEqualTo(b.beforeTokens());
        assertThat(a.afterTokens()).isEqualTo(b.afterTokens());
        assertThat(summarizer.calls.get()).isZero();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private ConsoleBudgetPort adapter(boolean enabled, TokenCounter counter) {
        return new ConsoleBudgetAdapter(
                new GlobalContextPolicy(enabled, 128_000, 16_000, 0.10),
                new ContextAssembler(counter, Thresholds.defaults()),
                summarizer,
                catalog);
    }

    private static BudgetInput input(ModelRequest legacy, int maxOutputTokens) {
        return new BudgetInput(legacy, "console:test", maxOutputTokens);
    }

    private static ModelRequest legacy(List<ModelMessage> messages) {
        return new ModelRequest(MODEL, STABLE, "", 0.37, 512, Duration.ofSeconds(11),
                Map.of("lookup", Map.of("type", "object")), messages);
    }

    /**
     * 一条 USER 锚 + 12 组 ASSISTANT/TOOL 可丢轮 + 末条 USER 当前输入。
     * 汉字按 1 token 的启发式口径，before 必然越过 W2/W3 水位；
     * 早期 TOOL 带唯一标记，W1 外置 / W2 整轮裁剪后必须消失。
     */
    private static List<ModelMessage> pressuredHistory() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.user("原始任务锚"));
        String big = "压".repeat(150);
        for (int i = 0; i < 12; i++) {
            String marker = (i < 3 ? "EARLY-TOKEN-" : "LATE-TOKEN-") + i;
            messages.add(ModelMessage.assistant("助手回答" + marker + big));
            messages.add(ModelMessage.toolResult("lookup", "工具结果" + marker + big));
        }
        messages.add(ModelMessage.user(CURRENT));
        return messages;
    }

    private static String texts(ModelRequest request) {
        StringBuilder out = new StringBuilder(request.systemPrompt());
        for (ModelMessage m : request.messages()) {
            out.append('\n').append(m.content());
        }
        return out.toString();
    }

    private static long count(List<ModelMessage> messages, String exact) {
        return messages.stream().filter(m -> exact.equals(m.content())).count();
    }

    private static ModelDefinition definition(String name, int window, int maxInput) {
        return new ModelDefinition(UUID.nameUUIDFromBytes(name.getBytes()).toString(),
                "test", "agent-chat", name, null, null, false, true, true, false,
                null, true, 0, null, null, null,
                new ModelContextCapabilities(window, maxInput, null, false, false));
    }

    private static final class StubCatalog implements ModelCatalogPort {
        List<ModelDefinition> definitions = List.of();
        boolean failOnRead;
        int reads;

        @Override
        public List<ModelDefinition> list() {
            reads++;
            if (failOnRead) {
                throw new AssertionError("⛔ 关闭态不应读取模型能力");
            }
            return definitions;
        }

        @Override
        public Optional<ModelDefinition> get(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelDefinition save(ModelDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ModelDefinition> defaultForScenario(String scenario) {
            throw new UnsupportedOperationException("⛔ 能力读取不得走场景默认模型");
        }

        @Override
        public Optional<ModelDefinition> byScenarioAndName(String scenario, String modelName) {
            throw new UnsupportedOperationException("⛔ 能力读取必须精确匹配全目录");
        }
    }

    private static final class CountingSummarizer implements Summarizer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<String> summarize(List<ModelMessage> delta, String oldSummary) {
            calls.incrementAndGet();
            return Optional.of("绝不该出现的 W3 摘要");
        }
    }

    /** 关闭态专用：一旦被调用就是测试失败，不能用普通异常掩盖断链。 */
    private static final class ExplodingCounter implements TokenCounter {
        @Override
        public int count(ContextBlocks blocks) {
            throw new AssertionError("⛔ 关闭态不应进入 token 计数");
        }
    }

    /** 启用态专用：模拟装配内核运行时故障，验证 adapter 投影为稳定拒因。 */
    private static final class FailingCounter implements TokenCounter {
        @Override
        public int count(ContextBlocks blocks) {
            throw new IllegalStateException("模拟装配内核 token 计数故障");
        }
    }
}
