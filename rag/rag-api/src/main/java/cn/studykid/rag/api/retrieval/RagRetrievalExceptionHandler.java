package cn.studykid.rag.api.retrieval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 检索面治理性拒绝的原因收口（2026-11-10 E2E 缺陷 D-03 的第二层修复）。
 *
 * <p><b>为什么必须有这层</b>：{@link RagRetrievalController} 把「嵌入模型服务不可达」
 * 映射成 {@code 503 + reason}，但 Spring 默认的 {@code /error} 只回
 * {@code {timestamp,status,error,path}}——<b>reason 既不出现在响应体，也不进服务端日志</b>
 * （2026-11-10 实测：停掉本机 Ollama 后 503 的日志里搜不到 {@code RAG_EMBEDDING_BASE_URL}）。
 * 运维拿到一个没有原因的 503，只能去查网络/数据库，而真实处置只有一条：
 * 把嵌入服务与模型拉起来。管理面已有同款收口（{@code RagManagementExceptionHandler}），
 * 检索面此前漏了。
 *
 * <p><b>⚠️ 为什么用 {@code assignableTypes} 限定到本控制器</b>：检索面与管理面共用一个
 * Web 容器，不限定范围的 advice 会把管理面的错误语义带到检索面；反之亦然。
 *
 * <p>⚠️ 只回 {@code reason}，<b>不</b>回堆栈、不回上游响应体原文
 * （后者可能带凭据或语料片段）。5xx 的原因写服务端日志，4xx 记 info。
 */
@RestControllerAdvice(assignableTypes = RagRetrievalController.class)
public class RagRetrievalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalExceptionHandler.class);

    /**
     * 治理性拒绝（{@link ResponseStatusException}）→ 原状态码 + 原因。
     *
     * @param ex 带原因的状态异常
     * @return 同状态码，响应体带可执行原因
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason();
        if (status.is5xxServerError()) {
            log.error("检索面请求失败 status={} reason={}", status.value(), reason, ex);
        } else {
            log.info("检索面请求被拒 status={} reason={}", status.value(), reason);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", HttpStatus.valueOf(status.value()).getReasonPhrase());
        body.put("message", reason == null ? "请求未被接受" : reason);
        return ResponseEntity.status(status).body(body);
    }
}
