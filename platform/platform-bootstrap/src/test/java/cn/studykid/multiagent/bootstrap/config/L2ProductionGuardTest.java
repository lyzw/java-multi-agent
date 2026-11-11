package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L2 判分骨架的<b>跨文件结构守卫</b>。
 *
 * <p>⚠️ 为什么单测不够：questionId 从事实仓储到判定器要跨 4 个模块、
 * 判分源 / 判定器 / 预算是三个可替换 bean，任何一处回退都会表现为
 * <b>「看板显示未启用 / 样本不足」而不是报错</b>——这正是本项目反复出现的静默失真形状。
 * 本类只锁<b>结构性红线</b>；真实判分逻辑由行为单测与闭环 IT 覆盖。
 *
 * <p>⚠️ 反向验证原则（每条都问过「撤掉修复点它会红吗」，2026-11-11 九项全红）：
 * <ul>
 *   <li>yml 把 {@code "[MiniMax-M2.5]"} 写回裸键 → Spring Binder 拆层，单价绑定守卫红；</li>
 *   <li>删 SQL 里的 {@code jsonb_typeof} 类型闸门 → 事实链守卫红；</li>
 *   <li>把 {@code resolve(questionId)} 改回 {@code resolve(scenario)} → 精确关联守卫红；</li>
 *   <li>加一行 {@code recordEvalSample} → 人机判分分离守卫红；</li>
 *   <li>删 {@code @ConditionalOnProperty(...llm-judge.enabled...)} → 默认 fail-closed 守卫红；</li>
 *   <li>judge 里出现 {@code cost_amount} / {@code usage_record} → 成本隔离守卫红；</li>
 *   <li>闭环 IT 改成直连 {@code l2.evaluate(...)} → 编排入口守卫红；</li>
 *   <li>采集器把 {@code run.questionId()} 改成 {@code null} → 闭环 IT 的 true 判分用例红；</li>
 *   <li>{@code recordAttributionGrade} 顺手写 {@code sampled = TRUE} → 闭环 IT 的人审隔离断言红。</li>
 * </ul>
 *
 * <p>⚠️ 读源码前剥离注释、保留字符串字面量：否则注释里提到某常量就会让守卫假绿。
 */
class L2ProductionGuardTest {

    // ── 相对 platform reactor 根 ──────────────────────────────────
    private static final String FACTS_PORT =
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/metrics/TraceFactPort.java";
    private static final String QUESTION_TAG =
            "agent-ports/src/main/java/cn/studykid/multiagent/ports/metrics/QuestionIdTag.java";
    private static final String FACTS_REPO =
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/JdbcTraceFactRepository.java";
    private static final String COLLECTOR =
            "agent-application/src/main/java/cn/studykid/multiagent/application/metrics/AgentMetricCollector.java";
    private static final String EVAL_SERVICE =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/TraceEvaluationService.java";
    private static final String L2_EVALUATOR =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/L2AttributionEvaluator.java";
    private static final String REF_SOURCE =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/ReferenceAnswerSource.java";
    private static final String LOCAL_BANK =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/LocalFileReferenceAnswerSource.java";
    private static final String JUDGE =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/ModelAttributionJudge.java";
    private static final String BUDGET =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/EvaluationCostBudget.java";
    private static final String WIRING =
            "agent-application/src/main/java/cn/studykid/multiagent/application/evaluation/EvaluationConfiguration.java";
    private static final String APP_YML =
            "platform-bootstrap/src/main/resources/application.yml";
    private static final String CLOSED_LOOP_IT =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/messaging/L2AttributionClosedLoopIT.java";

    @Test
    @DisplayName("⛔ questionId 必须从 run.input_payload 经类型闸门 + 白名单进入事实端口，并沿编排入口传递")
    void questionIdFlowsFromFactPortThroughOrchestrationEntry() {
        String repo = code(FACTS_REPO);
        assertThat(repo)
                .as("没有 jsonb_typeof 闸门，-&gt;&gt; 会把整个 JSON 对象转成题面文本")
                .contains("jsonb_typeof(input_payload -> 'questionId') = 'string'")
                .as("题目标识必须经白名单，非法值降级 null，不猜题号")
                .contains("QuestionIdTag.normalize(rs.getString(\"question_id\"))");

        String port = code(FACTS_PORT);
        assertThat(port).contains("@Nullable String questionId");
        // ⚠️ 铁律 11：兼容构造器必须逐字段显式传值，不能靠 record 默认值静默清空新字段。
        assertThat(port).contains("agentVersion, null, null);");

        // ⚠️ 铁律 6：守卫从编排入口进，不能只验端口被谁调用。
        assertThat(code(COLLECTOR))
                .contains("raiseEvaluation(metric, run.questionId())");
        assertThat(code(EVAL_SERVICE))
                .contains("l2.evaluate(tenantId, runId, traceId, scenario, questionId)");
    }

    @Test
    @DisplayName("⛔ 判分源只认 questionId 精确关联；机器判分绝不写人工标注表")
    void evaluatorUsesExactQuestionIdAndNeverTouchesHumanSampling() {
        String evaluator = code(L2_EVALUATOR);
        assertThat(evaluator)
                .contains("referenceSource.resolve(normalizedQuestionId)")
                .contains("UNGRADED_NO_QUESTION_ID")
                .contains("metrics.recordAttributionGrade(traceId, tenantId, correct.get())")
                .as("机器结论混进 agent_eval_sample 会把「人工抽样覆盖率」变成假指标")
                .doesNotContain("recordEvalSample(")
                .as("回退按题面 / 场景关联会让「精确关联」退化成模糊猜测")
                .doesNotContain("resolve(scenario")
                .doesNotContain("resolve(question");

        String ref = code(REF_SOURCE);
        assertThat(ref).contains("Optional<ReferenceAnswer> resolve(@NonNull String questionId)");
        // ⚠️ 题库对象只允许两个字段：多一个 studentQuestion 就多一份不可保留原文。
        String record = between(ref, "record ReferenceAnswer(", "}");
        assertThat(record).contains("questionId", "standardAnalysis");
        assertThat(record)
                .as("题库对象只允许主键 + 标准解析：多一个题面字段就多一份不可保留原文")
                .doesNotContain("String question,", "String scenario", "student");
    }

    @Test
    @DisplayName("⛔ 生产默认双开关关闭且不注册 judge；白名单比 scenario 更严")
    void productionDefaultsStayFailClosed() {
        String wiring = code(WIRING);
        assertThat(wiring)
                .as("判定器必须只在明确开启时注册；默认注册 NoOp 会冒充已接线")
                .contains("@ConditionalOnProperty(name = \"agent.quality.eval.llm-judge.enabled\", "
                        + "havingValue = \"true\")")
                .as("关闭时装配 NoReferenceAnswerSource（恒 empty）")
                .contains("return new NoReferenceAnswerSource();")
                .as("同类型多 Bean 由注入点 @Qualifier 决定，实现不得加 @Primary")
                .doesNotContain("@Primary");

        String yml = raw(APP_YML);
        String l2 = between(yml, "llm-judge:", "reference-source:");
        String source = between(yml, "reference-source:", "# ── 分层抽样");
        assertThat(l2).contains("enabled: false");
        assertThat(source).contains("enabled: false");

        assertThat(code(QUESTION_TAG))
                .as("questionId 不允许中文/空白：否则题面原文会被当成题库主键")
                .contains("[A-Za-z0-9_.:-]+")
                .doesNotContain("\\p{L}");
    }

    @Test
    @DisplayName("⛔ judge 成本走独立科目：绝不写 usage_record / cost_amount / 业务指标端口")
    void judgeCostIsPhysicallyIsolatedFromBusinessCost() {
        String judge = code(JUDGE);
        assertThat(judge)
                .contains("budget.tryReserve(")
                .contains("budget.settle(")
                .contains("EvaluationCostBudget.SUBJECT")
                .as("三段外部文本必须分别围栏，不能混进可信系统提示")
                .contains("promptGuard.wrapToolResult(");
        assertThat(countOf(judge, "promptGuard.wrapToolResult("))
                .as("题面 / Agent 归因 / 标准解析，三段缺一不可")
                .isEqualTo(3);
        assertThat(judge)
                .as("judge 一花钱就写业务账，会让单 Run 成本随评估频率虚高")
                .doesNotContain("usage_record", "cost_amount", "AgentMetricPort", "recordAttributionGrade");

        String budget = code(BUDGET);
        assertThat(budget).doesNotContain("usage_record", "agent_trace_metric", "AgentMetricPort");

        String yml = raw(APP_YML);
        assertThat(yml)
                .as("没有 MiniMax-M2.5 单价：业务 Run 成本按 0 记，judge 直接拒绝调用")
                .as("模型名含点号必须用方括号字面键，裸键会被 Spring Binder 拆成 MiniMax-M2 + 子键 5")
                .contains("\"[MiniMax-M2.5]\":")
                .contains("input-per-1k-tokens: \"0.002100\"")
                .contains("output-per-1k-tokens: \"0.008400\"")
                .contains("daily-cost-limit-yuan:")
                .contains("model: \"MiniMax-M2.5\"");
    }

    @Test
    @DisplayName("⛔ 本地题库加载期 fail-closed：重复题号 / 非法题号 / 未知版本都拒绝启动")
    void localBankFailsClosedOnMalformedData() {
        String bank = code(LOCAL_BANK);
        assertThat(bank)
                .contains("QuestionIdTag.normalize(idNode.asText())")
                .contains("EXPECTED_VERSION")
                .contains("MAX_FILE_BYTES")
                .as("重复 questionId 不拒绝会让判分随加载顺序漂移")
                .contains("重复 questionId")
                .as("空题库是安全默认，但「解析为空」是数据错误，必须拒绝")
                .contains("standardAnalysis 不能为空");
    }

    @Test
    @DisplayName("⛔ 闭环 IT 必须从采集编排入口进，只替换网络出口，且真库断言人机分离")
    void closedLoopItEntersThroughOrchestrationNotPorts() {
        String it = code(CLOSED_LOOP_IT);
        assertThat(it).as("闭环 IT 源码不能为空").isNotEmpty();
        // ⚠️ 铁律 6：直连端口的「有一行」不算闭环。
        assertThat(it).contains("collector.collect(");
        assertThat(it)
                .as("允许替换的只有模型出口这一个网络边界")
                .contains("@MockitoBean(name = \"chatStreamGateway\")");
        assertThat(it)
                .as("直连 l2 / 判分源 / 判定器会绕过「采集器真的调了评估」这条断链")
                .doesNotContain("l2.evaluate(", "referenceSource.resolve(", "judge.judgeCorrect(");
        assertThat(it)
                .contains("attribution_correct")
                .contains("sampled")
                .contains("human_verdict")
                .contains("agent_eval_sample")
                .contains("usage_record")
                .contains("questionId")
                .contains("agent.quality.eval.llm-judge.enabled=true")
                .contains("agent.quality.eval.reference-source.enabled=true");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static String code(String relativePath) {
        return stripComments(raw(relativePath));
    }

    private static String raw(String relativePath) {
        try {
            return Files.readString(platformRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + relativePath, e);
        }
    }

    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform reactor 根: "
                + System.getProperty("user.dir"));
    }

    private static long countOf(String text, String needle) {
        long count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String between(String text, String start, String end) {
        int a = text.indexOf(start);
        if (a < 0) {
            return "";
        }
        int b = text.indexOf(end, a + start.length());
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    /** 剥离注释但保留字符串字面量（text block 按普通字符串处理）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    out.append(src.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
            }
            out.append(c);
        }
        return out.toString();
    }
}
