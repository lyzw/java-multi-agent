package cn.studykid.multiagent.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link ConsoleChatController.ChatStreamRequest} 的 JSON 反序列化契约测试。
 *
 * <p>⚠️ <b>为什么必须有这一测试</b>：{@link ConsoleChatControllerTest} 是直接构造
 * 控制器 + 预置 {@code ChatStreamRequest}，<b>完全绕过了 HTTP 请求体反序列化</b>。
 * 若 Jackson 3 对嵌套 record 的反序列化（属性名 / 类型 / 嵌套 {@code ChatMessage}）
 * 不匹配前端实际发出的 JSON，端点会在运行时 400/500，而单测全绿——
 * 这正是「单测通过、生产挂掉」的盲区。本测试直接锁死前端契约。
 *
 * <p>前端 {@code directChatTransport} 发出的负载形如：
 * {@code {messages:[{role,content}...], modelId, systemPrompt, temperature, maxTokens}}。
 */
class ConsoleChatRequestDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("完整负载：messages / modelId / systemPrompt / temperature / maxTokens 全部落位")
    void fullPayloadDeserializes() throws Exception {
        String json = """
                {
                  "modelId": "MiniMax-M2.5",
                  "systemPrompt": "你是一名初中数学辅导老师。",
                  "temperature": 0.3,
                  "maxTokens": 512,
                  "messages": [
                    {"role": "user", "content": "判别式小于零是什么意思？"},
                    {"role": "assistant", "content": "表示方程无实数解。"},
                    {"role": "user", "content": "能举个例子吗？"}
                  ]
                }
                """;

        ConsoleChatController.ChatStreamRequest req =
                mapper.readValue(json, ConsoleChatController.ChatStreamRequest.class);

        assertEquals("MiniMax-M2.5", req.modelId());
        assertEquals("你是一名初中数学辅导老师。", req.systemPrompt());
        assertEquals(0.3, req.temperature());
        assertEquals(512, req.maxTokens());
        assertFalse(req.messages().isEmpty(), "messages 必须非空");

        List<ConsoleChatController.ChatStreamRequest.ChatMessage> msgs = req.messages();
        assertEquals(3, msgs.size());
        assertEquals("user", msgs.get(0).role());
        assertEquals("判别式小于零是什么意思？", msgs.get(0).content());
        assertEquals("assistant", msgs.get(1).role());
        assertEquals("能举个例子吗？", msgs.get(2).content());
    }

    @Test
    @DisplayName("缺省可选字段：省略 modelId/systemPrompt/temperature/maxTokens 不报错，且为 null")
    void optionalFieldsDefaultNull() throws Exception {
        String json = """
                {"messages": [{"role": "user", "content": "hi"}]}
                """;

        ConsoleChatController.ChatStreamRequest req =
                mapper.readValue(json, ConsoleChatController.ChatStreamRequest.class);

        assertNull(req.modelId(), "未传 modelId 应为 null（后端回落默认模型）");
        assertNull(req.systemPrompt(), "未传 systemPrompt 应为 null（后端回落空串）");
        assertNull(req.temperature(), "未传 temperature 应为 null（后端回落默认 0.7）");
        assertNull(req.maxTokens(), "未传 maxTokens 应为 null（后端回落默认 1024）");
        assertEquals(1, req.messages().size());
    }

    @Test
    @DisplayName("空 messages 数组也能反序列化（后续由控制器 fail-closed 判 400）")
    void emptyMessagesDeserializes() throws Exception {
        String json = """
                {"messages": []}
                """;

        ConsoleChatController.ChatStreamRequest req =
                mapper.readValue(json, ConsoleChatController.ChatStreamRequest.class);

        assertTrue(req.messages().isEmpty(), "空 messages 必须能被反序列化");
    }

    @Test
    @DisplayName("整数 temperature / maxTokens 也能接受（前端 InputNumber 可能传整型）")
    void integerScalarFieldsAccepted() throws Exception {
        String json = """
                {"messages": [{"role": "user", "content": "hi"}], "temperature": 1, "maxTokens": 2048}
                """;

        ConsoleChatController.ChatStreamRequest req =
                mapper.readValue(json, ConsoleChatController.ChatStreamRequest.class);

        assertEquals(1.0, req.temperature());
        assertEquals(2048, req.maxTokens());
    }
}
