package cn.studykid.rag.management.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;

/**
 * 管理面异常到 HTTP 状态的收口（RAG-D11 第四刀·第二刀）。
 *
 * <p><b>为什么必须单独收口 {@link RagOpsUnavailableException}</b>：
 * 平台全局语料的写通道（运维数据源）没配置时，实现层抛的是这个运行时异常。
 * 若不加处理，Spring 按未受检异常给出 <b>500</b>，
 * 而 500 的运维含义是「服务坏了」——真实情况是
 * 「这个部署根本不具备全局写能力」，两者要做的处置完全不同。
 * 更糟的是控制台会把 500 显示成未知错误，运维于是去查数据库连接。
 *
 * <p><b>⚠️ 503 而不是 4xx</b>：调用方的请求本身没错（身份、票据、参数都合法），
 * 缺的是<b>服务端能力</b>；4xx 会让前端把它归入「用户错误」而不去补配置。
 *
 * <p><b>⚠️ 绝不回落到应用角色</b>：本处理器只做状态映射，不做重试或降级。
 * 「ops 未接入就用 {@code rag_app} 试一把」的结果不是「写得差一点」，而是
 * RLS 按 {@code scope='TENANT'} 的写策略<b>静默滤掉全局行</b>——
 * 影响 0 行、不报错、控制台报告成功（见 {@code RagOpsSessionPort} 的说明）。
 *
 * <p><b>为什么用 {@code assignableTypes} 限定范围</b>：
 * 检索面（{@code rag-api}）与管理面共用同一个 Web 容器，
 * 一个不限定范围的全局 advice 会把管理面的错误语义带到检索面去。
 */
@RestControllerAdvice(assignableTypes = RagManagementController.class)
public class RagManagementExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RagManagementExceptionHandler.class);

    /**
     * 运维写通道未接入 → 503，响应体带<b>缺的配置键名</b>。
     *
     * <p>⚠️ 键名直接进响应体不是信息泄漏：它是 {@code rag.ops.datasource.*}
     * 这类<b>属性名</b>（文档里就有），而不是值。
     * 运维拿到「缺哪个键」才能一次修好；只给 503 会让人去查网络与数据库。
     *
     * @param ex 通道异常
     * @return 503 + 原因
     */
    @ExceptionHandler(RagOpsUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onOpsUnavailable(RagOpsUnavailableException ex) {
        log.warn("平台全局语料写通道未接入：missing={} message={}",
                ex.missingConfiguration(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "RAG_OPS_UNAVAILABLE");
        body.put("message", ex.getMessage());
        body.put("missingConfiguration", ex.missingConfiguration());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
