package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩 <b>S2-G2-b L1 真跑闭环 IT</b>的源码守卫。
 *
 * <p>⚠️ 本类钉死的是「真跑 IT 不会退化成假绿」的四类结构：
 * <ol>
 *   <li>模型桩必须<b>按真实送出的 ModelRequest 计费</b>；模型网关必须用
 *       {@code BeanDefinitionRegistryPostProcessor} 强制替换同名 Bean。
 *       实测「@Bean(name = "observableModelGateway") + @Primary」会被生产配置反向覆盖，
 *       退回 {@code FakeModelGateway} 固定 usage 10:20 —— 压缩发生也看不出账上 token 下降。</li>
 *   <li>OFF / TIERED 双臂对照、账本同源、回放底座、工具轮次配对、token 真降断言齐全；
 *       挤出压力夹具参数不可削弱。</li>
 *   <li>L1 Worker 真正送模型的请求必须来自 {@code assembly.request()}，
 *       不得回退 {@code legacyRequest}（M7-b 接线失效时算法仍绿但请求字节没变）。</li>
 *   <li>配套反证脚本 {@code scripts/g2-b-counterproof.sh} 的三点撤点、中断保护、
 *       红/绿判定与 md5 复原三件套齐全。</li>
 * </ol>
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@link Pattern#find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（全串语义，长文件恒真 = 假绿源）。
 */
class ContextCompressionG2GuardTest {

    private static final String IT =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/context/"
                    + "ContextCompactionL1RealRunIT.java";
    private static final String LOOP =
            "agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/RunExecutionLoop.java";
    private static final String COUNTERPROOF = "scripts/g2-b-counterproof.sh";
    private static final String RABBIT_IT =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/messaging/"
                    + "TraceCollectEndToEndIT.java";
    private static final String DB_REPLAY_IT =
            "platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/messaging/"
                    + "TraceMetricContentAccuracyIT.java";
    private static final String METRIC_REPO =
            "agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/persistence/"
                    + "JdbcAgentMetricRepository.java";
    private static final String REPLAY_COUNTERPROOF = "scripts/g2-c-replay-counterproof.sh";

    @Test
    @DisplayName("✅ RV-G2-G1：强制替换网关 + 按请求计费，堵死固定 usage 假绿")
    void realRunItUsesForcedGatewayOverrideAndRequestBasedBilling() {
        String code = src(IT);

        // ① 必须靠 BFPP 在配置解析完成后替换；同名 @Bean 覆盖实测会被生产反向覆盖。
        assertThat(code)
                .as("⛔ 缺少 BeanDefinitionRegistryPostProcessor 强制替换："
                        + "会退回生产 FakeModelGateway 固定 usage，压缩发生也测不出")
                .contains("static BeanDefinitionRegistryPostProcessor "
                        + "g2ObservableGatewayDefinitionOverride()")
                .contains("registry.removeBeanDefinition(\"observableModelGateway\")")
                .contains("definition.setInstanceSupplier(")
                .contains("new ObservableModelGateway(")
                .contains("beanFactory.getBean(ModelCallObserverChain.class)");
        assertNoPattern(code,
                Pattern.quote("@Bean(name = \"observableModelGateway\")"),
                "⛔ 退回会被生产配置反向覆盖的同名 @Bean 写法（Javadoc 注释已剥离）");
        for (String obsolete : List.of("g2RecordingModelCallObserver",
                "g2RecordingExecutionObserver")) {
            assertNoPattern(code, Pattern.quote(obsolete),
                    "⛔ 多余的 Observer 适配 Bean 复活：按 RecordingObservers 注入会双候选启动失败");
        }

        // ② 计费桩必须来自真实请求；固定 usage 会让「token 下降」与压缩彻底解耦。
        String billing = "long inputTokens = TOKEN_COUNTER.count("
                + "RequestContextProjection.project(request));";
        assertThat(countLiteral(code, billing))
                .as("⛔ 按真实请求计费的锚点不是唯一一处：模型桩计费被改写或复制")
                .isEqualTo(1);
        assertThat(code)
                .as("⛔ usage 未沿用桩算出的 inputTokens")
                .contains("new ModelResponse.ModelUsage(inputTokens, 8)");
        assertNoPattern(code, "ModelUsage\\(\\s*1000",
                "⛔ 固定 usage 复活：RV-G2-01 撤点形态回归，IT 会静默假绿");

        // ③ 一个 RecordingObservers 同时进两条链；不能再拆成两个适配 Bean。
        assertThat(countLiteral(code, "RecordingObservers recordingObservers()"))
                .as("⛔ RecordingObservers 不是单 Bean 注册")
                .isEqualTo(1);
        assertThat(code)
                .as("⛔ RecordingObservers 不再同时实现两类 Observer，压缩事件采集会空转")
                .contains("implements ModelCallObserver, ExecutionObserver");
    }

    @Test
    @DisplayName("✅ RV-G2-G2：OFF/TIERED 双臂、账本同源、回放/工具配对与 token 真降断言齐全")
    void realRunItKeepsTwoArmComparisonAndLedgerAssertions() {
        String code = src(IT);

        // ① 同一 Spring 上下文打开总开关，两个冻结策略只能是一 OFF / 一 TIERED。
        assertThat(code)
                .as("⛔ 总开关未打开 / IT 无环境门禁")
                .contains("\"agent.context.enabled=true\"")
                .contains("@EnabledIfEnvironmentVariable(named = \"IT_PG_URL\", matches = \".+\")")
                .as("⛔ 双臂冻结策略缺失或退化")
                .contains("policyJson(false, \"OFF\")")
                .contains("policyJson(true, \"TIERED\")")
                .contains("RunResult off = runOnce(offAgentId)")
                .contains("RunResult tiered = runOnce(tieredAgentId)");

        // ② 账本必须逐轮等于真实送出请求，不能只比一个总数。
        assertThat(code)
                .as("⛔ usage_record 与真实请求未同源校验")
                .contains("assertUsageMatchesRequests(\"OFF\", off)")
                .contains("assertUsageMatchesRequests(\"TIERED\", tiered)")
                .contains("RequestContextProjection.project(request)")
                .contains("isEqualTo(expected)");

        // ③ 行为分流 + 真降断言 + 回放底座 + 工具轮次配对，缺一即为弱断言。
        assertThat(code)
                .as("⛔ OFF 压缩事件未断言为空 / TIERED 压缩事件未断言非空")
                .contains("assertThat(off.compactions())")
                .contains("assertThat(tiered.compactions())")
                .contains("isNotEmpty()")
                .as("⛔ 压缩事件可能是空转（droppedTokens<=0 或 after>=before）")
                .contains("assertThat(event.droppedTokens())")
                .contains("assertThat(event.afterTokens())")
                .contains(".isPositive()")
                .contains(".isLessThan(event.beforeTokens())")
                .as("⛔ 核心断言丢失：usage_record.input_tokens 必须真下降")
                .contains("assertThat(tieredLast)")
                .contains("assertThat(tieredTotal)")
                .contains(".isLessThan(offLast)")
                .contains(".isLessThan(offTotal)")
                .as("⛔ 回放底座 / 工具轮次配对断言丢失")
                .contains("assertThat(off.messageRoles())")
                .contains("assertThat(tiered.messageRoles())")
                .contains("assertFirstUserAnchorOnce(\"TIERED\", tiered.requests())")
                .contains("assertToolCallPairs(\"TIERED\", tiered.requests())");

        // ④ 清理纪律：review_task 必须按租户谓词删除（ReviewTaskFixtureGuardTest 同纪律）。
        assertThat(code)
                .as("⛔ 清理未带 tenant_id 谓词：FORCE RLS 下静默 0 行，夹具泄漏到下一用例")
                .contains("exec(\"DELETE FROM platform.review_task WHERE tenant_id = ?::uuid\", tenant)");

        // ⑤ 挤出压力窗口参数不可削弱：低于触发线就永远压缩，等于没测。
        assertThat(code)
                .as("⛔ 挤出压力夹具被削弱：OFF 不裁 / TIERED 触发的窗口消失")
                .contains("private static final int TOOL_ROUNDS = 5;")
                .contains("private static final int WINDOW_TOKENS = 16_000;")
                .contains("private static final int CONTEXT_WINDOW_TOKENS = 32_768;")
                .contains("private static final int WEAKNESS_COUNT = 50;")
                .contains("private static final int KNOWLEDGE_POINT_CHARS = 100;")
                .contains("\"maxInputTokens\":16000")
                .contains("\"triggerRatio\":0.7")
                .contains("\"targetRatio\":0.4");
    }

    @Test
    @DisplayName("✅ RV-G2-G4：L1 真正送模型的请求来自统一装配入口，不是 legacy 回退")
    void l1SendsAssembledRequestNotLegacyFallback() {
        String loop = src(LOOP);

        assertThat(loop)
                .as("⛔ L1 没有调用统一装配入口：M7-b 接线失效，算法绿但请求字节没变")
                .contains("RequestAssemblyResult assembly = assembleRequest(")
                .as("⛔ 真正送模型的请求不是 assembly.request()：撤掉修复点仍会全绿")
                .contains("ModelRequest request = assembly.request();");
        assertNoPattern(loop, Pattern.quote("ModelRequest request = legacyRequest;"),
                "⛔ L1 回退到 legacyRequest 的撤点形态残留：统一装配入口被绕过");
        assertThat(countLiteral(loop, "ModelRequest request = assembly.request();"))
                .as("⛔ 送模型请求赋值不是唯一一处：可能存在第二条旁路")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ RV-G2-C-G1：双通道重投证据非空转（ack/覆盖时间戳/事实冻结/副作用冻结）")
    void doubleChannelReplayEvidenceIsNonVacuous() {
        String rabbitRaw = rawPlatform(RABBIT_IT);
        String dbRaw = rawPlatform(DB_REPLAY_IT);
        String rabbit = src(RABBIT_IT);
        String db = src(DB_REPLAY_IT);

        // ① S2 边界必须以源码 Javadoc 固定：不把「压缩态不重复落库」误算进当前阶段。
        for (String raw : List.of(rabbitRaw, dbRaw)) {
            assertThat(raw)
                    .as("⛔ S2 边界声明丢失：重投验收只能覆盖「输出确定、无副作用、不二次计费」")
                    .contains("S2 边界</b>：本类只验「同一输入重投 → 输出确定、无副作用、不二次计费」")
                    .contains("{@code checkpoint} 表 /")
                    .contains("{@code ContextCheckpointPort} / compaction 幂等键尚未落地");
        }

        // ② Rabbit：两次投递必须各自 ack，且 computed_at 前移证明第二次真的被消费。
        assertThat(rabbit)
                .as("⛔ Rabbit 缺少「第二次真的被消费」的非空转证据")
                .contains("awaitAckCount(acksBefore + 1)")
                .contains("awaitAckCount(acksAfterFirst + 1)")
                .contains("awaitMetricRecomputedAfter(computedAfterFirst)")
                .contains(".isAfter(computedAfterFirst)")
                .contains("awaitMetricRowCount(1)")
                .contains("productionExecuteQueueDepth()");
        assertThat(countLiteral(rabbit,
                "traceCollectMessageSender.send(\"agent.trace.collect\", msg);"))
                .as("⛔ Rabbit 通道必须显式发送两次同一事件")
                .isEqualTo(2);
        assertThat(rabbit)
                .as("⛔ Rabbit 未冻结 Run 事实 / 派生副作用 / 非零计费夹具")
                .contains("assertThat(captureExecutionFacts())")
                .contains("assertThat(captureSideEffectFacts())")
                .contains("isEqualTo(baseline)")
                .contains("isEqualTo(sideAfterFirst)")
                .contains("insertUsage()")
                .contains("insertRunStep()");
        assertNoPattern(rabbit, "Thread\\.sleep\\(1500\\)",
                "⛔ 固定长睡等待复活：重投证据必须等可观察事件，不能靠 sleep 碰运气");

        // ③ DB：PG 处理条数 + computed_at 前移，且 PENDING/死信/事实/副作用均不重复。
        assertThat(db)
                .as("⛔ DB 通道缺少「第二次真的被消费」的非空转证据")
                .contains("awaitPgProcessedAtLeast(processedBefore + 1)")
                .contains("awaitMetricRecomputedAfter(computedAfterFirst)")
                .contains(".isAfter(computedAfterFirst)")
                .contains("metricRowCount())");
        assertThat(countLiteral(db, "traceCollectSender.send(TraceCollectEvent.TYPE, msg);"))
                .as("⛔ DB 通道内容级 IT 必须在同一用例内二次投递同一事件")
                .isGreaterThanOrEqualTo(3);
        assertThat(db)
                .as("⛔ DB 未冻结 Run 事实 / 计费 / 工具明细 / 评估副作用 / 队列终态")
                .contains("assertThat(captureExecutionFacts())")
                .contains("assertThat(captureSideEffectFacts())")
                .contains("isEqualTo(runBefore)")
                .contains("isEqualTo(sideAfterFirst)")
                .contains("metricLong(\"input_tokens\")")
                .contains("pendingQueueRows()")
                .contains("deadLetterRows()")
                .contains(".isZero()");
        assertNoPattern(db, "Thread\\.sleep\\(1500\\)",
                "⛔ 固定长睡等待复活：重投证据必须等可观察事件，不能靠 sleep 碰运气");

        // ④ 被反证的生产修复点必须仍在：指标覆盖写 + 工具明细先删后插。
        String repo = src(METRIC_REPO);
        assertThat(repo)
                .as("⛔ 生产幂等修复点丢失：双通道重投会退化成翻倍或异常")
                .contains("ON CONFLICT (trace_id) DO UPDATE SET")
                .contains("computed_at = now()")
                .contains("DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?");

        // ⑤ 反证脚本必须同时覆盖两点撤点、中断保护、旧报告清理与复原三件套。
        String script = rawRepo(REPLAY_COUNTERPROOF);
        assertThat(script)
                .as("⛔ 双通道重投反证脚本缺项：无法证明「撤掉修复点会红」")
                .contains("RV-G2-C-01")
                .contains("RV-G2-C-02")
                .contains("ON CONFLICT (trace_id) DO UPDATE SET")
                .contains("DELETE FROM platform.agent_trace_tool_call WHERE trace_id = ?")
                .contains("trap 'restore; exit 130' INT TERM")
                .contains("rm -f \"${txt}\" \"${xml}\"")
                .contains("anchor_count")
                .contains("md5 -q")
                .contains("git diff --check");
    }

    @Test
    @DisplayName("✅ RV-G2-G3：三点反证、中断保护、红/绿判定与 md5 复原三件套齐全")
    void counterproofScriptKeepsThreePointsAndRestoreDiscipline() {
        String script = ContextCompressionS1GuardTest.read(repoRoot().resolve(COUNTERPROOF));

        assertThat(script)
                .as("⛔ 反证撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-G2-01")
                .contains("RV-G2-02")
                .contains("RV-G2-03")
                .as("⛔ RV-G2-01 撤点形态（固定 usage）丢失")
                .contains("long inputTokens = 1000L; // RV-G2-01")
                .as("⛔ RV-G2-02 撤点形态（TIERED 降级 OFF）丢失")
                .contains("policyJson(false, \"OFF\")); // RV-G2-02")
                .as("⛔ RV-G2-03 撤点形态（L1 回退 legacyRequest）丢失")
                .contains("ModelRequest request = legacyRequest;")
                .as("⛔ 生产侧撤点必须覆盖 RunExecutionLoop.java")
                .contains("LOOP_REL=platform/agent-worker/src/main/java/"
                        + "cn/studykid/multiagent/worker/execute/RunExecutionLoop.java");
        assertThat(script)
                .as("⛔ 中断保护缺失：SIGTERM/SIGINT 会残留撤点源码造成静默失真")
                .contains("trap 'restore; exit 130' INT TERM")
                .as("⛔ 未清理旧报告：编译失败时会读到上次报告，制造假红/假绿")
                .contains("rm -f \"${REPORT}\" \"${REPORT_XML}\"")
                .as("⛔ 红/绿判定锚点缺失")
                .contains("grep -qE 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0'")
                .contains("grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'")
                .as("⛔ 复原三件套缺失（含双文件 md5 一致性校验）")
                .contains("anchor_count")
                .contains("marker_count")
                .contains("md5 -q")
                .contains("git diff --check");
    }

    /** 读原始源码（含 Javadoc）：用于校验「S2 边界」这类注释契约。 */
    private static String rawPlatform(String rel) {
        return ContextCompressionS1GuardTest.read(platformRoot().resolve(rel));
    }

    private static String rawRepo(String rel) {
        return ContextCompressionS1GuardTest.read(repoRoot().resolve(rel));
    }

    private static String src(String rel) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(rawPlatform(rel));
    }

    private static Path platformRoot() {
        return ContextCompressionS1GuardTest.platformRoot();
    }

    private static Path repoRoot() {
        return ContextCompressionS1GuardTest.repoRoot();
    }

    private static int countLiteral(String text, String literal) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(literal, from)) >= 0) {
            count++;
            from += literal.length();
        }
        return count;
    }

    /** 锚点出现即失败（Pattern.find 取反，规避 doesNotMatch 全串假绿）。 */
    private static void assertNoPattern(String text, String regex, String reason) {
        assertThat(Pattern.compile(regex, Pattern.DOTALL).matcher(text).find())
                .as(reason + "（Pattern.find 取反，非全串匹配）")
                .isFalse();
    }
}
