package cn.studykid.rag.management.api;

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

    /**
     * 治理性拒绝（{@link ResponseStatusException}）→ 原状态码 + <b>原因</b>。
     *
     * <p><b>⚠️ 为什么不能交给默认处理</b>：Spring 默认的 {@code /error}
     * 只回 {@code {timestamp,status,error,path}}，把 {@code reason} 丢掉了
     * （实测 409 响应体里没有任何可执行信息）。而 409 在本服务里承载的是
     * <b>几乎是全部治理规则</b>：
     * <ul>
     *   <li>状态机的非法转移；</li>
     *   <li>「尚未通过人工抽检」；</li>
     *   <li>「审核员不得审核自己采集的来源」「双人复核缺复核人 / 与执行人同一人」；</li>
     *   <li>「无法确认抽检结论」（fail-closed）。</li>
     * </ul>
     * 这些 reason 是写给管理员看的、<b>直接可照做</b>的下一步；
     * 丢掉它只剩一个笼统的 Conflict，控制台只能展示通用文案，
     * 管理员根本不知道该改什么（实测踩到：界面提示「这一步不被允许」，
     * 真实原因是没做抽检）。
     *
     * <p>⚠️ 只回 {@code reason}，不回堆栈：响应体是给人看的原因，不是诊断包。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason();
        if (status.is5xxServerError()) {
            log.error("管理面请求失败 status={} reason={}", status, reason, ex);
        } else {
            log.info("管理面请求被拒 status={} reason={}", status, reason);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", java.time.Instant.now().toString());
        body.put("status", status.value());
        body.put("error", HttpStatus.valueOf(status.value()).getReasonPhrase());
        body.put("message", reason == null ? "请求未被接受" : reason);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 入参形态校验失败（{@link IllegalArgumentException}）→ <b>400</b> + 领域层原话。
     *
     * <p><b>⚠️ 为什么必须有这层兜底</b>：管理面的领域对象（{@code SourceRegistration}、
     * {@code MetadataConfirmation}、{@code VerificationDecision} 等）都在
     * <b>构造期</b>做入参校验并抛 {@link IllegalArgumentException}。
     * 控制器里那些**显式**写了 try/catch 的端点把它转成了 400，
     * 但只要有一处漏写（实测踩到：{@code POST /sources} 漏了），
     * 这个异常就会一路冒泡成 <b>500</b>——
     * <ul>
     *   <li>运维含义被带偏：500 = 「服务坏了」，真实情况是「请求没填全」；</li>
     *   <li>响应体被 Spring 默认的 {@code /error} 接管，只剩
     *       {@code {timestamp,status,error,path}}——<b>领域层那条可执行的原因被丢掉</b>，
     *       管理员看到的是笼统的「Internal Server Error」；</li>
     *   <li>控制台按 5xx 分流成「服务端故障、控制台侧修不了」，
     *       管理员于是反复重试——重试永远不会成功。</li>
     * </ul>
     * 所以这里把它收成 400：<b>调用方输入问题</b>，且<b>原样带回原因</b>
     * （如「ISBN 与 DOI 至少提供一个（否则无法进入校验流程）」），
     * 让管理员知道下一步该做什么。
     *
     * <p>⚠️ <b>只收 4xx 语义明确的入参异常</b>：{@link IllegalArgumentException}
     * 在本服务里的语义就是「入参形态不合法」，因此可以安全地整体归一为 400。
     * 真正的服务故障（库不可达、通道未接入）走的是别的异常类型
     * （如 {@link RagOpsUnavailableException} → 503），不会被这里误吞。
     *
     * @param ex 入参校验异常
     * @return 400 + 领域层原话
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException ex) {
        String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "请求参数不合法"
                : ex.getMessage();
        log.info("管理面入参校验失败 reason={}", reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", java.time.Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.put("message", reason);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
