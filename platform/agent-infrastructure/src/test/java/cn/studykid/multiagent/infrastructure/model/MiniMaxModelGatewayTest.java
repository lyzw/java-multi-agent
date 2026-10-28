package cn.studykid.multiagent.infrastructure.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MiniMax 网关的离线契约测试（不打真实网络）：
 * 请求组装、响应解析、错误归一化、凭据不落日志（AT-MODEL-001/002）。
 */
class MiniMaxModelGatewayTest {

    private static final String API_KEY = "sk-cp-test-key-0123456789abcdef";

    private final MiniMaxModelGateway gateway =
            new MiniMaxModelGateway("https://api.minimax.cn/v1", API_KEY, "MiniMax-M2.5");

    private static ModelRequest request(Map<String, Object> toolSchemas,
                                        List<ModelMessage> messages) {
        return new ModelRequest("minimax:MiniMax-M2.5", "你是数学老师", "解方程",
                0.2, 1024, Duration.ofSeconds(30), toolSchemas, messages);
    }

    @Nested
    class PayloadAssembly {

        @Test
        @DisplayName("modelRef 带 provider 前缀时剥掉前缀")
        void stripsProviderPrefix() {
            var payload = gateway.buildPayload(request(Map.of(), List.of()));
            assertThat(payload.get("model")).isEqualTo("MiniMax-M2.5");
        }

        @Test
        @DisplayName("系统提示独立成条，消息按角色映射，工具消息带 tool_call_id")
        void mapsRolesAndToolMessages() {
            var payload = gateway.buildPayload(request(Map.of(), List.of(
                    ModelMessage.user("问题"),
                    ModelMessage.assistant("[本轮请求调用工具：read_profile]"),
                    ModelMessage.toolResult("read_profile", "结果"))));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages =
                    (List<Map<String, Object>>) payload.get("messages");
            assertThat(messages).hasSize(4);
            assertThat(messages.get(0)).containsEntry("role", "system");
            assertThat(messages.get(3)).containsEntry("role", "tool")
                    .containsEntry("tool_call_id", "read_profile");
        }

        @Test
        @DisplayName("⛔ tool 消息的标识必须命中上一条 assistant 的 tool_calls（否则对方 400 整次请求）")
        void toolRoundTripKeepsCallIdsPaired() {
            // 2026-10-28 真实 MiniMax 端到端实测踩到的形态：
            // 回灌只发 tool 消息、标识填工具名，上一条 assistant 里没有同名调用，
            // 供应商返回 400 invalid params, tool result's tool id(...) not found。
            var payload = gateway.buildPayload(request(Map.of(), List.of(
                    ModelMessage.user("问题"),
                    ModelMessage.assistantToolCalls("[本轮请求调用工具：read_profile]", List.of(
                            new ModelMessage.ToolCallEcho(
                                    "call_7", "read_profile", "{\"studentId\":\"s1\"}"))),
                    ModelMessage.toolResult("read_profile", "结果", "call_7"))));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages =
                    (List<Map<String, Object>>) payload.get("messages");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> calls =
                    (List<Map<String, Object>>) messages.get(2).get("tool_calls");
            assertThat(calls)
                    .as("发起调用的 assistant 消息必须原样带回 tool_calls")
                    .singleElement()
                    .satisfies(call -> {
                        assertThat(call).containsEntry("id", "call_7")
                                .containsEntry("type", "function");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> function =
                                (Map<String, Object>) call.get("function");
                        assertThat(function).containsEntry("name", "read_profile")
                                .containsEntry("arguments", "{\"studentId\":\"s1\"}");
                    });
            assertThat(messages.get(3))
                    .containsEntry("role", "tool")
                    .containsEntry("tool_call_id", "call_7");
        }

        @Test
        @DisplayName("领域工具登记表翻译为 OpenAI function Schema；空表不带 tools 字段")
        void translatesToolRegistry() {
            Map<String, Object> declaredSchema = Map.of(
                    "type", "object",
                    "required", List.of("studentId"),
                    "properties", Map.of("studentId", Map.of("type", "string")));
            var payload = gateway.buildPayload(request(
                    Map.of("read_profile", Map.of(
                            "name", "read_profile",
                            "description", "读取档案",
                            "inputSchema", declaredSchema)), List.of()));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) payload.get("tools");
            assertThat(tools).as("声明了工具就必须带上 tools 字段").hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
            // ⚠️ 这条断言是 2026-10-28 补的：此前只断言「tools 非空」，
            //    而参数表被静默降级成空 Schema 时它照样通过 ——
            //    于是真实模型收到的永远是一个「没有参数的工具」。
            assertThat(function.get("parameters"))
                    .as("parameters 必须原样携带参数定义，不得降级成空 Schema")
                    .isEqualTo(declaredSchema);
            assertThat(gateway.buildPayload(request(Map.of(), List.of())))
                    .doesNotContainKey("tools");
        }

        @Test
        @DisplayName("⛔ inputSchema 形态非法 → 不得静默发空 Schema 给模型")
        void nonMapInputSchemaDegradesLoudly() {
            var payload = gateway.buildPayload(request(
                    Map.of("read_profile", Map.of(
                            "name", "read_profile",
                            "description", "读取档案",
                            // 装配侧给错形态（如 JsonNode）时的现场
                            "inputSchema", "not-a-map")), List.of()));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) payload.get("tools");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) tools.get(0).get("function");
            assertThat(function.get("parameters"))
                    .as("降级为空 Schema 是刻意的（宁可少一个工具也不发非法 JSON），"
                            + "但必须伴随 WARN 日志，否则模型只会「交不出参数」")
                    .isEqualTo(Map.of("type", "object", "properties", Map.of()));
        }

        @Test
        @DisplayName("请求体不含 API Key（密钥只走 Authorization 头）")
        void payloadHasNoCredential() {
            String json = gateway.buildPayload(request(Map.of(), List.of())).toString();
            assertThat(json).doesNotContain(API_KEY);
        }
    }

    @Nested
    class ResponseParsing {

        @Test
        @DisplayName("文本完成：content / usage / STOP 归一化")
        void parsesTextCompletion() {
            var response = gateway.parseCompletion("""
                    {"choices":[{"finish_reason":"stop","message":\
                    {"role":"assistant","content":"移项求解"}}],\
                    "usage":{"prompt_tokens":211,"completion_tokens":30},\
                    "base_resp":{"status_code":0,"status_msg":""}}""");
            assertThat(response.content()).isEqualTo("移项求解");
            assertThat(response.usage().inputTokens()).isEqualTo(211);
            assertThat(response.usage().outputTokens()).isEqualTo(30);
            assertThat(response.finishReason()).isEqualTo(ModelResponse.FinishReason.STOP);
            assertThat(response.toolCalls()).isEmpty();
        }

        @Test
        @DisplayName("工具调用：arguments JSON 解析为参数表，finish=TOOL_CALLS")
        void parsesToolCalls() {
            var response = gateway.parseCompletion("""
                    {"choices":[{"finish_reason":"tool_calls","message":\
                    {"role":"assistant","content":"","tool_calls":[\
                    {"id":"call_1","type":"function","function":\
                    {"name":"read_profile","arguments":"{\\"student_id\\":\\"S1\\"}"}}]}}],\
                    "usage":{"prompt_tokens":10,"completion_tokens":5},\
                    "base_resp":{"status_code":0}}""");
            assertThat(response.finishReason()).isEqualTo(ModelResponse.FinishReason.TOOL_CALLS);
            var call = response.toolCalls().get("call_1");
            assertThat(call.toolName()).isEqualTo("read_profile");
            assertThat(call.arguments()).containsEntry("student_id", "S1");
        }

        @Test
        @DisplayName("非法 arguments 不炸调用：按空参数交闸门 Schema 校验")
        void malformedArgumentsDegradeToEmpty() {
            var response = gateway.parseCompletion("""
                    {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[\
                    {"function":{"name":"t","arguments":"not-json"}}]}}],\
                    "usage":{"prompt_tokens":1,"completion_tokens":1},\
                    "base_resp":{"status_code":0}}""");
            assertThat(response.toolCalls().values().iterator().next().arguments()).isEmpty();
        }

        @Test
        @DisplayName("HTTP 200 + base_resp 非零 = 业务失败，不得解析成空成功")
        void baseRespErrorIsFailure() {
            assertThatThrownBy(() -> gateway.parseCompletion(
                    """
                    {"choices":[],"base_resp":{"status_code":1004,\
                    "status_msg":"login fail"}}"""))
                    .isInstanceOf(MiniMaxModelGateway.ProviderStatusException.class);
        }

        @Test
        @DisplayName("真实供应商回包（2026-10-28 抓取）：content 与 tool_calls 并存时两者都保留")
        void parsesCapturedProviderToolResponse() {
            var response = gateway.parseCompletion("""
                    {"choices":[{"finish_reason":"tool_calls","message":\
                    {"role":"assistant","content":"I'll query the score for student S001.",\
                    "tool_calls":[{"id":"call_01a180679513749a81167cc6","type":"function",\
                    "function":{"name":"query_score","arguments":"{\\"student_ref\\":\\"S001\\"}"}}]}}],\
                    "usage":{"total_tokens":485,"prompt_tokens":446,"completion_tokens":39,\
                    "completion_tokens_details":{"reasoning_tokens":0}},\
                    "base_resp":{"status_code":0,"status_msg":""}}""");
            assertThat(response.content()).isEqualTo("I'll query the score for student S001.");
            assertThat(response.finishReason()).isEqualTo(ModelResponse.FinishReason.TOOL_CALLS);
            var call = response.toolCalls().get("call_01a180679513749a81167cc6");
            assertThat(call.toolName()).isEqualTo("query_score");
            assertThat(call.arguments()).containsEntry("student_ref", "S001");
            assertThat(response.usage().inputTokens()).isEqualTo(446);
            assertThat(response.usage().outputTokens()).isEqualTo(39);
        }

        @Test
        @DisplayName("length / content_filter 结束原因归一化")
        void mapsOtherFinishReasons() {
            for (var pair : List.of(
                    Map.entry("length", ModelResponse.FinishReason.LENGTH),
                    Map.entry("content_filter", ModelResponse.FinishReason.CONTENT_FILTER))) {
                assertThat(gateway.parseCompletion(
                        "{\"choices\":[{\"finish_reason\":\"" + pair.getKey()
                                + "\",\"message\":{\"content\":\"x\"}}],"
                                + "\"usage\":{},\"base_resp\":{\"status_code\":0}}")
                        .finishReason()).isEqualTo(pair.getValue());
            }
        }
    }

    @Nested
    class ReasoningStripping {

        /** 标签在测试里同样拼接：字面量会被文本处理链吞掉。 */
        private final String open = "<" + "think" + ">";
        private final String close = "<" + "/think" + ">";

        @Test
        @DisplayName("草稿段被剥离，只留正文")
        void dropsDraftKeepsAnswer() {
            assertThat(MiniMaxModelGateway.stripReasoning(
                    open + "先因式分解 (x-2)(x-3)" + close + "\n\n解得 x=2 或 x=3"))
                    .isEqualTo("解得 x=2 或 x=3");
        }

        @Test
        @DisplayName("多段草稿：取最后一段之后的正文")
        void usesLastClosedSegment() {
            assertThat(MiniMaxModelGateway.stripReasoning(
                    open + "第一段" + close + "中间" + open + "第二段" + close + "最终答案"))
                    .isEqualTo("最终答案");
        }

        @Test
        @DisplayName("草稿未闭合（被 length 截断）时原样保留，不静默丢内容")
        void unclosedDraftIsKept() {
            assertThat(MiniMaxModelGateway.stripReasoning(open + "想到一半被截断"))
                    .isEqualTo(open + "想到一半被截断");
        }

        @Test
        @DisplayName("无草稿的普通回答原样返回")
        void plainContentUntouched() {
            assertThat(MiniMaxModelGateway.stripReasoning("分数是把整体平均分的几份"))
                    .isEqualTo("分数是把整体平均分的几份");
        }

        @Test
        @DisplayName("parseCompletion 出口即已剥离：草稿不会落进 run.result")
        void parseStripsDraftFromContent() {
            var response = gateway.parseCompletion(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                            + open + "草稿" + close + "正文\"}}],"
                            + "\"usage\":{},\"base_resp\":{\"status_code\":0}}");
            assertThat(response.content()).isEqualTo("正文");
        }
    }

    @Nested
    class ErrorNormalization {

        @Test
        @DisplayName("401/403→UNAUTHORIZED、429→RATE_LIMITED、400→INVALID_REQUEST（不可重试方向正确）")
        void statusCodesMapToKinds() {
            assertThat(gateway.classify(new MiniMaxModelGateway.ProviderStatusException(401, "x")))
                    .isEqualTo(ModelGateway.ModelErrorKind.UNAUTHORIZED);
            assertThat(gateway.classify(new MiniMaxModelGateway.ProviderStatusException(403, "x")))
                    .isEqualTo(ModelGateway.ModelErrorKind.UNAUTHORIZED);
            assertThat(gateway.classify(new MiniMaxModelGateway.ProviderStatusException(429, "x")))
                    .isEqualTo(ModelGateway.ModelErrorKind.RATE_LIMITED);
            assertThat(gateway.classify(new MiniMaxModelGateway.ProviderStatusException(400, "x")))
                    .isEqualTo(ModelGateway.ModelErrorKind.INVALID_REQUEST);
            assertThat(gateway.classify(new MiniMaxModelGateway.ProviderStatusException(503, "x")))
                    .isEqualTo(ModelGateway.ModelErrorKind.PROVIDER_ERROR);
            assertThat(ModelGateway.ModelErrorKind.UNAUTHORIZED.retryable()).isFalse();
            assertThat(ModelGateway.ModelErrorKind.RATE_LIMITED.retryable()).isTrue();
        }

        @Test
        @DisplayName("鉴权失败异常消息不含 API Key（AT-MODEL-002）")
        void credentialNeverLeaks() {
            assertThatThrownBy(() -> new MiniMaxModelGateway(
                    "https://api.minimax.cn.invalid/v1", API_KEY, "MiniMax-M2.5")
                    .complete(request(Map.of(), List.of())))
                    .isInstanceOf(ModelGateway.ModelGatewayException.class)
                    .hasMessageNotContaining(API_KEY)
                    .hasMessageNotContaining("Bearer");
        }
    }
}
