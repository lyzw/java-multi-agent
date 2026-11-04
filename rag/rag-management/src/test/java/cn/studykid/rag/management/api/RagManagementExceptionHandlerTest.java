package cn.studykid.rag.management.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理面异常到 HTTP 状态的收口测试（RAG-D11 第四刀·第二刀）。
 *
 * <p><b>⚠️ 守的是「错误语义」不是措辞</b>：错误码选错会把排查方向带偏。
 * <ul>
 *   <li>入参形态问题（缺 ISBN/DOI、哈希格式非法）→ <b>400</b>，
 *       且必须<b>原样带回领域层原因</b>——那是管理员能照着改的下一步；</li>
 *   <li>治理性拒绝（ResponseStatusException）→ 原状态码 + 原因；</li>
 *   <li>运维写通道未接入 → <b>503</b>（服务端能力缺失，不是用户错误）。</li>
 * </ul>
 */
class RagManagementExceptionHandlerTest {

    private final RagManagementExceptionHandler handler = new RagManagementExceptionHandler();

    @Test
    @DisplayName("⛔ 入参校验失败（IllegalArgumentException）→ 400，并原样带回原因")
    void illegalArgumentBecomesBadRequestWithReason() {
        // ⚠️ 复现 2026-11-04 实测：登记缺 ISBN/DOI 时若漏成 500，
        //    响应体被 Spring 默认的 /error 接管，领域层那句
        //    「ISBN 与 DOI 至少提供一个」就丢了——管理员只剩一个笼统的
        //    Internal Server Error，还会被引导去查数据库。
        ResponseEntity<Map<String, Object>> response =
                handler.onIllegalArgument(new IllegalArgumentException(
                        "ISBN 与 DOI 至少提供一个（否则无法进入校验流程）"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(400, body.get("status"));
        assertEquals("Bad Request", body.get("error"));
        assertEquals("ISBN 与 DOI 至少提供一个（否则无法进入校验流程）", body.get("message"),
                "必须原样带回领域层原因，不能丢掉可执行信息");
    }

    @Test
    @DisplayName("⛔ 入参校验失败即使没有 message 也要给出可读的兜底文案")
    void illegalArgumentWithoutMessageStillHasReadableMessage() {
        ResponseEntity<Map<String, Object>> response =
                handler.onIllegalArgument(new IllegalArgumentException());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("message"));
        assertTrue(((String) body.get("message")).contains("参数"),
                "兜底文案必须说清是入参问题，实际: " + body.get("message"));
    }

    @Test
    @DisplayName("治理性拒绝（ResponseStatusException）→ 原状态码 + 原因")
    void responseStatusKeepsStatusAndReason() {
        ResponseEntity<Map<String, Object>> response =
                handler.onResponseStatus(new ResponseStatusException(
                        HttpStatus.CONFLICT, "审核员不得审核自己采集的来源"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(409, body.get("status"));
        assertEquals("Conflict", body.get("error"));
        assertEquals("审核员不得审核自己采集的来源", body.get("message"));
    }

    @Test
    @DisplayName("治理性拒绝没有 reason 时也要有可读文案（不能是 null）")
    void responseStatusWithoutReasonStillHasReadableMessage() {
        ResponseEntity<Map<String, Object>> response =
                handler.onResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.get("message"));
        assertTrue(((String) body.get("message")).length() > 0);
    }
}
