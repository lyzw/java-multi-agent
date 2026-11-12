package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;

/**
 * S3-M8-a 请求侧压缩态净化的跨模块源码守卫（ADR-013 D49 / G1 A.5）。
 *
 * <p>⚠️ 本类不重复行为测试，而是钉死「接线形状」不会被静默退化：
 * <ol>
 *   <li>Controller 只逐字透传未验 state，不验签 / 不解码 / 不落库；</li>
 *   <li>编排边界是唯一净化点，验过才下传、丢弃即全量历史；M8-b-2 起在白名单内
 *       按冻结线序发射第 14 事件，关闭态仍不发（G1 A.5 升级，不删守卫）；</li>
 *   <li>信封用独立 HMAC-SHA256 + 域分离 + 严格状态重建，零 JWT 私钥 / 零 IO / 零 JSON 库；</li>
 *   <li>{@code policyVersion} 由 D17 裁决支持类同源派生，组合根不造第二策略真相；</li>
 *   <li>本批压缩态不落库 / 不发 MQ / 不接 {@code ObservableModelGateway} 观测链。</li>
 * </ol>
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@link Pattern#find()} 取反，不用
 * AssertJ {@code doesNotMatch}（全串语义，长文件恒真 = 假绿源）。
 */
class ContextCompressionM8aGuardTest {

    private static final String CONTROLLER =
            "platform/agent-api/src/main/java/cn/studykid/multiagent/api/chat/AgentChatController.java";
    private static final String REQUEST =
            "platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/AgentChatRequest.java";
    private static final String ORCHESTRATOR =
            "platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/agentchat/AgentChatOrchestrator.java";
    private static final String VERIFIER =
            "platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/ContextStateVerifier.java";
    private static final String ENVELOPE =
            "platform/agent-context/src/main/java/cn/studykid/multiagent/context/ContextStateEnvelope.java";
    private static final String ADAPTER =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextStateVerifierAdapter.java";
    private static final String CONFIG =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextStateVerifierConfiguration.java";
    private static final String PROPERTIES =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/AgentContextProperties.java";
    private static final String VERSION_PORT =
            "platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/ContextPolicyVersionPort.java";
    private static final String VERSION_ADAPTER =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextPolicyVersionAdapter.java";
    private static final String VERSION_CONFIG =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextPolicyVersionConfiguration.java";
    private static final String SUPPORT =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextPolicyResolutionSupport.java";
    private static final String ASSEMBLY_ADAPTER =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextRequestAssemblyAdapter.java";
    // ── M8-b-2 发射侧（与接收侧对称；D50：新端口放 agentchat，避开冻结的 ports/context）──
    private static final String EMITTER_PORT =
            "platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/agentchat/ContextCheckpointEmitter.java";
    private static final String EMITTER_ADAPTER =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextCheckpointEmitterAdapter.java";
    private static final String EMITTER_CONFIG =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/ContextCheckpointEmitterConfiguration.java";
    private static final String YAML =
            "platform/platform-bootstrap/src/main/resources/application.yml";
    private static final String COUNTERPROOF = "scripts/m8a-context-state-counterproof.sh";

    private static final String RESOLVE_CALL = "ContextPolicyResolutionSupport.resolve(";

    @Test
    @DisplayName("✅ RV-M8A-01：Controller 只逐字透传原始 state，唯一净化点在编排边界")
    void rawStateIsOpaqueAtControllerAndPurifiedOnlyInOrchestrator() {
        String controller = src(CONTROLLER);
        String request = src(REQUEST);
        String orchestrator = src(ORCHESTRATOR);

        assertThat(controller)
                .as("⛔ Controller 提前做状态校验 / 解码：超长与篡改会被静默改写成「无状态」")
                .doesNotContain("ContextStateVerifier")
                .doesNotContain("ContextPolicyVersionPort")
                .doesNotContain("ContextStateEnvelope")
                .doesNotContain("StructuredStateCodec")
                .doesNotContain("JSON.parse")
                .doesNotContain("atob(")
                .doesNotContain("URLDecoder");
        assertThat(count(controller, "safe.contextState())"))
                .as("⛔ 原始 state 没有逐字下传给编排边界")
                .isEqualTo(1);
        assertThat(request)
                .as("⛔ 中性请求 DTO 不是全参 8 字段：compat 构造器会静默清空后加字段")
                .contains("record AgentChatRequest(")
                .contains("@Nullable String contextState");

        assertThat(count(orchestrator, "applyContextState("))
                .as("⛔ 净化入口不是「run 唯一调用 + 唯一方法声明」：状态会走旁路")
                .isEqualTo(2);
        assertThat(count(orchestrator, "stateVerifier.verify("))
                .as("⛔ 编排边界没有调用中性校验端口")
                .isEqualTo(1);
        // G1 A.5 / M8-b-2 升级（#73）：D17 唯一裁决端口现在有两处调用 —— 接收侧
        // applyContextState 与发射侧 emitCheckpoint 共用同一端口 / 同一 Input 口径，仍是
        // 唯一策略真相（绝非自造第二套读取）。恰好 2 处：多一处是旁路真相，少一处是发射侧漏裁。
        assertThat(count(orchestrator, "policyVersions.resolve("))
                .as("⛔ D17 裁决调用点不是「接收 + 发射各一」：会出现第二真相或发射侧漏裁")
                .isEqualTo(2);
        assertThat(count(orchestrator, "new ContextPolicyVersionPort.Input("))
                .as("⛔ 两处裁决未共用同一中性 Input：发射侧与接收侧会按不同口径取策略")
                .isEqualTo(2);
        assertThat(orchestrator)
                .as("⛔ 验过 / 丢弃两条分支没有分别替换请求：下游会消费未验原始串")
                .contains("withContextState(request, accepted.stateJson())")
                .contains("withContextState(request, null)")
                .as("⛔ 同构副本必须逐字段带过，不得顺手改历史 / message / 场景")
                .contains("request.message(), request.history(), request.agentId(), request.topK(),")
                .contains("request.thinkingMode(), request.requestedTenantId(), request.scenario(),");
    }

    @Test
    @DisplayName("✅ RV-M8A-02：三态结果与 8 个丢弃原因冻结，策略指纹缺失即 fail-closed")
    void verifierContractIsClosedAndPolicyVersionIsRequired() {
        assertThat(ContextStateVerifier.Result.class.getPermittedSubclasses())
                .as("⛔ sealed Result 被扩容：调用方可能漏判新状态并静默放行")
                .containsExactlyInAnyOrder(
                        ContextStateVerifier.Accepted.class,
                        ContextStateVerifier.Absent.class,
                        ContextStateVerifier.Discarded.class);
        assertThat(componentNames(ContextStateVerifier.Input.class))
                .as("⛔ 校验输入缺 expectedPolicyVersion：会从信封反推当前策略")
                .containsExactly("clientState", "tenantId", "historyTurns",
                        "expectedPolicyVersion");
        assertThat(componentNames(ContextStateVerifier.Accepted.class))
                .containsExactly("stateJson", "policyVersion", "upToTurn", "digest");
        assertThat(componentNames(ContextStateVerifier.Discarded.class))
                .containsExactly("reason");
        assertThat(ContextStateVerifier.DropReason.values())
                .as("⛔ 六项校验的失败形态被缩水：超长 / 结构 / HMAC / 密钥 / 过期 / 版本 / 租户 / 游标")
                .containsExactly(
                        ContextStateVerifier.DropReason.TOO_LARGE,
                        ContextStateVerifier.DropReason.MALFORMED,
                        ContextStateVerifier.DropReason.HMAC_MISMATCH,
                        ContextStateVerifier.DropReason.KEY_UNAVAILABLE,
                        ContextStateVerifier.DropReason.EXPIRED,
                        ContextStateVerifier.DropReason.POLICY_VERSION_MISMATCH,
                        ContextStateVerifier.DropReason.TENANT_MISMATCH,
                        ContextStateVerifier.DropReason.TURN_CURSOR_AHEAD);

        String verifier = src(VERIFIER);
        assertThat(verifier)
                .as("⛔ 空白策略指纹被当成「使用信封版本」：组合根会制造第二套策略真相")
                .contains("expectedPolicyVersion = expectedPolicyVersion == null"
                        + " || expectedPolicyVersion.isBlank()")
                .contains("? null : expectedPolicyVersion")
                .contains("tenantId == null || tenantId.isBlank()")
                .contains("historyTurns < 0");
        assertNoPattern(verifier, "org\\.springframework|java\\.sql\\.|javax\\.sql\\.|"
                        + "cn\\.studykid\\.multiagent\\.context\\.",
                "⛔ 中性校验端口出现框架 / JDBC / 算法层反向依赖（ADR-011）");
    }

    @Test
    @DisplayName("✅ RV-M8A-03：信封独立 HMAC + 确定性载荷 + 严格状态重建，零 JWT / IO / JSON 库")
    void envelopeUsesIndependentHmacAndStrictStateRebuild() {
        String envelope = src(ENVELOPE);

        assertThat(envelope)
                .as("⛔ 信封不是独立域分离 HMAC：与 JWT/其他通道共用密钥会串改状态")
                .contains("HmacSHA256")
                .contains("Base64.getUrlEncoder().withoutPadding()")
                .contains("DOMAIN = \"l2cs1\"")
                .contains("TENANT_DOMAIN = DOMAIN + \".tenant.v1\"")
                .contains("MessageDigest.isEqual(")
                .as("⛔ 验签后未严格重建状态：重复键 / 非规范整数 / 摘要错配会进后续请求")
                .contains("StructuredStateCodec.fromJson(")
                .contains("StructuredStateCodec.digest(")
                .as("⛔ 六项校验的状态码被撤掉：静默丢弃会变成恒真吞异常")
                .contains("DropReason.TOO_LARGE")
                .contains("DropReason.MALFORMED")
                .contains("DropReason.HMAC_MISMATCH")
                .contains("DropReason.KEY_UNAVAILABLE")
                .contains("DropReason.EXPIRED")
                .contains("DropReason.POLICY_VERSION_MISMATCH")
                .contains("DropReason.TENANT_MISMATCH")
                .contains("DropReason.TURN_CURSOR_AHEAD");

        assertNoPattern(envelope, "org\\.springframework|java\\.sql\\.|javax\\.sql\\.|"
                        + "ObjectMapper|Gson|tools\\.jackson|com\\.fasterxml|"
                        + "JWT_PRIVATE_KEY|PLATFORM_JWT_PRIVATE_KEY|"
                        + "Cipher|AES|RSA|Instant\\.now|System\\.currentTimeMillis|"
                        + "System\\.getenv|System\\.getProperty|Files\\.|java\\.nio\\.file",
                "⛔ 纯算法信封引入 JWT / 加密 / 系统时钟 / IO / JSON 库： seal/verify 会不可复现");
        assertThat(envelope)
                .as("⛔ 载荷分界/摘要形状被改：跨版本 state 会被误验通过或误丢")
                .contains("FIELD_SEPARATOR")
                .contains("sha256:[0-9a-f]{64}")
                .contains("FIELD_COUNT = 6");
    }

    @Test
    @DisplayName("✅ RV-M8A-04：组合根只装配唯一 verifier；空密钥 / 空策略指纹 fail-closed")
    void verifierAdapterIsThinAndConfigIsIndependent() {
        String adapter = src(ADAPTER);
        String config = src(CONFIG);
        String props = src(PROPERTIES);
        String yaml = raw(YAML);

        assertThat(adapter)
                .as("⛔ 组合根自己实现 HMAC/状态解析：会出现第二套信封口径")
                .contains("ContextStateEnvelope.verify(")
                .doesNotContain("HmacSHA256")
                .doesNotContain("Base64.")
                .doesNotContain("StructuredStateCodec")
                .doesNotContain("ObjectMapper")
                .as("⛔ 空密钥 / 未裁决策略指纹必须静默丢弃，绝不能当「无 state」放行")
                .contains("if (input.clientState() == null)")
                .contains("new Absent()")
                .contains("if (hmacKey.length == 0)")
                .contains("DropReason.KEY_UNAVAILABLE")
                .contains("if (input.expectedPolicyVersion() == null)")
                .contains("DropReason.POLICY_VERSION_MISMATCH");

        assertThat(config)
                .as("⛔ verifier 不是唯一可覆盖 Bean：多实例会各用一套密钥/长度口径")
                .contains("@ConditionalOnMissingBean(ContextStateVerifier.class)")
                .contains("ContextStateVerifier contextStateVerifier(")
                .contains("properties.getStateHmacKey()")
                .contains("properties.getMaxStateChars()")
                .contains("properties.getCheckpointTtlSeconds()")
                .contains("Clock")
                .doesNotContain("cp_");
        assertThat(countBootstrapMain("ContextStateVerifier contextStateVerifier("))
                .as("⛔ 生产源码出现第二个 verifier 装配入口")
                .isEqualTo(1);

        assertThat(props)
                .as("⛔ 独立 HMAC 键没有安全默认值：影子期会误用空串以外的隐式密钥")
                .contains("private String stateHmacKey = \"\";");
        assertThat(yaml)
                .as("⛔ state HMAC 键与 JWT 私钥共用环境变量：跨通道伪造会静默成立")
                .contains("state-hmac-key: \"${AGENT_CONTEXT_STATE_HMAC_KEY:}\"")
                .doesNotContain("state-hmac-key: \"${JWT_PRIVATE_KEY");
    }

    @Test
    @DisplayName("✅ RV-M8A-05：policyVersion 同源裁决，组合根没有硬编码策略指纹")
    void policyVersionIsResolvedOnceAndNeverHardcoded() {
        String port = src(VERSION_PORT);
        String versionAdapter = src(VERSION_ADAPTER);
        String versionConfig = src(VERSION_CONFIG);
        String support = src(SUPPORT);
        String assemblyAdapter = src(ASSEMBLY_ADAPTER);

        assertThat(port)
                .as("⛔ 指纹裁决端口不是单一 resolve 操作：调用方会自造第二套策略读取入口")
                .contains("@Nullable String resolve(@NonNull Input input)")
                .contains("record Input(")
                .doesNotContain("cn.studykid.multiagent.context.");
        assertThat(support)
                .as("⛔ 运行期唯一策略读点不在共享支持类：M7 与 M8-a 会按不同策略版本工作")
                .contains("ContextPolicyReader.read(definition.get().generationParams())")
                .contains("ContextPolicyResolver.resolve(");
        assertThat(versionAdapter)
                .as("⛔ 指纹裁决没有走 M7 同一段裁决接线")
                .contains(RESOLVE_CALL)
                .contains("resolved.policy().policyVersion()")
                .as("⛔ 关闭态没有零读取短路 / 裁决异常没有 fail-soft")
                .contains("if (!global.enabled())")
                .contains("catch (RuntimeException e)");
        assertThat(assemblyAdapter)
                .as("⛔ M7 统一装配没有走同一段裁决接线")
                .contains(RESOLVE_CALL)
                .contains("EffectivePolicyProjection.toEffectivePolicy(resolved.policy())");
        assertThat(versionConfig)
                .as("⛔ 指纹裁决端口不是唯一可覆盖 Bean")
                .contains("@ConditionalOnMissingBean(ContextPolicyVersionPort.class)")
                .contains("ContextPolicyVersionPort contextPolicyVersionPort(");
        assertThat(countBootstrapMain("ContextPolicyVersionPort contextPolicyVersionPort("))
                .as("⛔ 生产源码出现第二个策略指纹装配入口")
                .isEqualTo(1);
        assertThat(countBootstrapMain("\"cp_"))
                .as("⛔ 组合根硬编码策略指纹：策略变更时旧 state 会被静默错误接受")
                .isZero();
    }

    @Test
    @DisplayName("✅ RV-M8A-06（G1 A.5 / M8-b-2 升级）：编排器白名单内正面发射；"
            + "原始 state 不进帧/审计/系统提示词，已验 state 只在不可信围栏内注入模型")
    void orchestratorNeverLeaksStateAndEmitsCheckpointInAllowList() {
        String orchestrator = src(ORCHESTRATOR);

        // ── ① G1 A.5 升级：第 14 事件从「禁止发射」改为「白名单内正面发射」（M6 守卫同口径）。
        //     编排器只按类型构造事件，绝不硬编码线名字符串（线名仍然只在 AgentChatEvent 冻结）。
        assertThat(orchestrator)
                .as("⛔ L2 未在白名单内发射第 14 事件：M8-b-2 契约空转")
                .contains("new AgentChatEvent.ContextCheckpoint(")
                .as("⛔ 成功收口 Done 未带版本：前端无法把 state 与 done 绑定")
                .contains("new AgentChatEvent.Done(")
                .contains("checkpoint == null ? null : checkpoint.version()");
        assertNoPattern(orchestrator, Pattern.quote("context.checkpoint"),
                "⛔ SSE 链硬编码 checkpoint 线名：线名应只在 AgentChatEvent 冻结");
        // 收口形状：失败收口 1 处 v1 兼容构造，成功收口 1 处带版本构造（旧 13 帧字节不变）。
        assertThat(count(orchestrator, "sink.accept(new AgentChatEvent.Done(false));"))
                .as("⛔ 失败收口 v1 兼容构造应恰好 1 处：旧客户端字节冻结")
                .isEqualTo(1);
        assertThat(count(orchestrator, "sink.accept(new AgentChatEvent.ContextCheckpoint("))
                .as("⛔ 第 14 事件发射点应恰好 1 处：多发射重复帧 / 少发射空转")
                .isEqualTo(1);

        // ── ② 核心负面原样保留：审计可信面（beginRecord / resultPayload）零压缩态令牌。
        //     这是「外部数据绝不污染审计」的底线，M8-b-2 发射标量事实也不得突破。
        String beginRecord = methodBody(orchestrator,
                "private @NonNull BeginRecord beginRecord(");
        String resultPayload = methodBody(orchestrator,
                "private static @NonNull String resultPayload(");
        for (String body : List.of(beginRecord, resultPayload)) {
            assertThat(body)
                    .as("⛔ 压缩态串 / 已验摘要 / 丢弃原因进入审计可信面：外部数据污染了可信面")
                    .doesNotContain("contextState")
                    .doesNotContain("stateJson")
                    .doesNotContain("upToTurn")
                    .doesNotContain("dropReason")
                    .doesNotContain("carryOver")
                    .doesNotContain("CONTEXT_CHECKPOINT");
        }
        // 审计的 checkpoint 标量事实必须是独立事件（Map.of 标量），绝不写 state 正文。
        String emitCheckpoint = methodBody(orchestrator,
                "private @Nullable CheckpointEmission emitCheckpoint(");
        assertThat(emitCheckpoint)
                .as("⛔ 发射侧未共用 D17 唯一裁决端口：会与接收侧按不同策略版本工作")
                .contains("policyVersions.resolve(")
                .as("⛔ 封包异常未 fail-soft：可选增强会中断主流程")
                .contains("catch (RuntimeException e)");
        assertNoPattern(emitCheckpoint, "state\\s*\\(\\)",
                "⛔ 发射侧读取了 state 信封正文：不透明载荷不应被 worker 解析");

        // ── ③ G1 A.5 白名单升级：buildMessages 允许「已验 stateJson 经不可信围栏注入」，
        //     但绝不裸拼 / 绝不进 systemPrompt / 绝不把 HMAC 信封原文当指令。
        String buildMessages = methodBody(orchestrator,
                "private static @NonNull List<ModelMessage> buildMessages(");
        assertThat(buildMessages)
                .as("⛔ 已验压缩态未走 PromptGuard 不可信围栏：裸拼会被当成指令执行")
                .contains("RAG_GUARD.wrapCompactedContext(")
                .as("⛔ carry-over 围栏未限定为 USER 块：注入到 systemPrompt 会提权")
                .contains("ModelMessage.user(RAG_GUARD.wrapCompactedContext(");
        // 围栏之外：buildMessages 不得直接拼接原始请求字段或 HMAC 信封令牌。
        for (String token : List.of("contextState()", "stateJson()", "ContextStateEnvelope", "verify(")) {
            assertNoPattern(buildMessages, Pattern.quote(token),
                    "⛔ buildMessages 出现 " + token + "：模型上下文构造不应解析 / 校验压缩态");
        }
    }

    @Test
    @DisplayName("✅ M8-b-2：发射端口/适配器/组合根保持中性 + 单一接线 + fail-soft（与接收侧对称）")
    void emitterPortAdapterAndRootRemainNeutralAndFailSoft() {
        String port = src(EMITTER_PORT);
        String adapter = src(EMITTER_ADAPTER);
        String config = src(EMITTER_CONFIG);

        // ── ① 中性端口：零算法层 / 框架 / JDBC 反向依赖（ADR-011 / C1），与接收侧 verifier 对称。
        //     D50：放 ports/agentchat，不进 RV-65 冻结的 ports/context 四接口目录。
        assertThat(port)
                .as("⛔ 发射端口契约不是「emit 返回 nullable」：空转/异常无法静默不发")
                .contains("@Nullable Emitted emit(")
                .as("⛔ 中性 DTO 全参构造丢失：compat 构造器会静默清空后加字段（铁律 #10）")
                .contains("record Input(")
                .contains("record Emitted(");
        assertNoPattern(port, "org\\.springframework|java\\.sql\\.|javax\\.sql\\.|"
                        + "cn\\.studykid\\.multiagent\\.context\\.",
                "⛔ 中性发射端口出现框架 / JDBC / 算法层反向依赖（ADR-011）");

        // ── ② 组合根 adapter：只做接线，封包算法全部委托纯算法层；零第二套实现。
        assertThat(adapter)
                .as("⛔ adapter 没委托 CheckpointDeltaFactory：组合根自造第二套增量算法")
                .contains("CheckpointDeltaFactory.build(")
                .as("⛔ adapter 没委托 ContextStateEnvelope.seal：封包/验签会口径漂移")
                .contains("ContextStateEnvelope.seal(")
                .as("⛔ 空独立密钥未 fail-soft：影子期会硬签发一个下一轮自己不认的 state")
                .contains("hmacKey.length == 0")
                .as("⛔ 封包异常未 fail-soft：可选增强失败会中断 RAG/回答/审计主流程")
                .contains("catch (RuntimeException e)");
        assertNoPattern(adapter, "java\\.sql\\.|javax\\.sql\\.|outbox_event|agent_message_queue|"
                        + "RabbitTemplate|ObservableModelGateway|ModelCallObserver",
                "⛔ 发射适配器落库 / 发 MQ / 接观测链：M8-b-2 只在内存封包");

        // ── ③ 唯一装配片：与接收侧共用 AgentContextProperties / Clock，非法配置启动期拒。
        assertThat(config)
                .as("⛔ 发射端口不是唯一可覆盖 Bean：会出现第二套封包实现")
                .contains("@ConditionalOnMissingBean(ContextCheckpointEmitter.class)")
                .contains("ContextCheckpointEmitter contextCheckpointEmitter(")
                .as("⛔ 未复用接收侧同一份配置：签发/校验会用不同密钥或长度口径")
                .contains("AgentContextProperties properties")
                .contains("getStateHmacKey()")
                .as("⛔ 非法 max-state-chars / TTL 未在启动期拒绝")
                .contains("getMaxStateChars() <= 0")
                .contains("getCheckpointTtlSeconds() <= 0");
        assertThat(countBootstrapMain("ContextCheckpointEmitter contextCheckpointEmitter("))
                .as("⛔ 生产源码出现第二个发射端口装配入口")
                .isEqualTo(1);
        assertThat(countBootstrapMain("\"cp_"))
                .as("⛔ 组合根硬编码策略指纹：发射侧与接收侧会按不同版本工作")
                .isZero();
    }

    @Test
    @DisplayName("✅ RV-M8A-07：压缩态不落库、不发 MQ、不接观测链")
    void contextStateIsNotPersistedOrObserved() {
        List<String> m8aSources = List.of(
                CONTROLLER, ORCHESTRATOR, VERIFIER, ENVELOPE, ADAPTER, CONFIG,
                VERSION_PORT, VERSION_ADAPTER, VERSION_CONFIG, SUPPORT);
        List<String> forbidden = List.of(
                "java.sql.", "javax.sql.", "Jdbc", "outbox_event", "agent_message_queue",
                "RabbitTemplate", "ObservableModelGateway", "ModelCallObserver",
                "onContextCompacted", "ExecutionObserver", "INSERT INTO",
                "execution_snapshot", "run_event", "run_step");
        for (String rel : m8aSources) {
            String code = src(rel);
            for (String token : forbidden) {
                assertThat(Pattern.compile(Pattern.quote(token)).matcher(code).find())
                        .as("⛔ %s 出现 %s：M8-a 请求侧压缩态不应落库 / 发 MQ / 接观测链",
                                rel, token)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("✅ RV-M8A-08：反证脚本带中断保护、精确锚点、红/绿判定与 md5 复原")
    void counterproofScriptKeepsDiscipline() {
        String script = raw(COUNTERPROOF);

        assertThat(script)
                .as("⛔ 撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-M8A-01")
                .contains("RV-M8A-02")
                .contains("RV-M8A-03")
                .contains("RV-M8A-04")
                .contains("RV-M8A-05")
                .contains("RV-M8A-06")
                .as("⛔ 中断保护缺失：SIGTERM/SIGINT 会残留撤点源码造成静默失真")
                .contains("trap restore EXIT")
                .contains("INT TERM")
                .as("⛔ 红/绿判定缺失：编译失败无报告时会被当作有效反证")
                .contains("expect_red")
                .contains("expect_green")
                .contains("Tests run:")
                .contains("Skipped: 0")
                .as("⛔ 复原三件套缺失")
                .contains("anchor_count")
                .contains("marker_count")
                .contains("md5_all")
                .contains("git diff --check");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static String src(String rel) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(raw(rel));
    }

    private static String raw(String rel) {
        return ContextCompressionS1GuardTest.read(
                ContextCompressionS1GuardTest.repoRoot().resolve(rel));
    }

    private static int count(String text, String needle) {
        return ContextCompressionS1GuardTest.countOccurrences(text, needle);
    }

    private static int countBootstrapMain(String needle) {
        Path root = ContextCompressionS1GuardTest.repoRoot()
                .resolve("platform/platform-bootstrap/src/main/java");
        int total = 0;
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                total += count(ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                        ContextCompressionS1GuardTest.read(file)), needle);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 platform-bootstrap 生产源码失败", e);
        }
        return total;
    }

    /** 配对括号取方法体，避免首个 '}' 在 catch / if 处提前截断造成假绿。 */
    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start).as("⛔ 找不到方法签名: %s", signature).isGreaterThanOrEqualTo(0);
        int open = code.indexOf('{', start);
        assertThat(open).as("⛔ 找不到方法体开括号: %s", signature).isGreaterThan(start);
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inChar = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open + 1, i);
                }
            }
        }
        throw new IllegalStateException("方法体括号不配对: " + signature);
    }

    /** 锚点出现即失败（Pattern.find 取反，规避 doesNotMatch 全串假绿）。 */
    private static void assertNoPattern(String text, String regex, String reason) {
        assertThat(Pattern.compile(regex, Pattern.DOTALL).matcher(text).find())
                .as(reason + "（Pattern.find 取反，非全串匹配）")
                .isFalse();
    }
}
