package cn.studykid.multiagent.api.run;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * Run 结果读取端点（P4-D10 / G5）。
 *
 * <p><b>为什么需要它</b>：
 * 结果页（P5）此前<b>只有硬编码的占位假数据</b>——编造的解题过程直接渲染给学生。
 * 本端点是 P5 的<b>唯一真实数据来源</b>。
 *
 * <p>⚠️ <b>404 不区分「不存在」与「无权限」</b>：
 * 若分别返回 404 与 403，攻击者可通过状态码差异<b>枚举 Run 是否存在</b>。
 * 故两种情形统一返回 404「资源不存在」（与 G3 端点同一纪律）。
 */
@RestController
public class ResultController {

    private static final Logger log = LoggerFactory.getLogger(ResultController.class);

    private final ResultQueryPort resultQuery;
    private final AuthorizationService authorization;
    private final RateLimitPort rateLimiter;
    private final RunController.CurrentPrincipal currentPrincipal;

    public ResultController(ResultQueryPort resultQuery,
                            AuthorizationService authorization,
                            RateLimitPort rateLimiter,
                            RunController.CurrentPrincipal currentPrincipal) {
        this.resultQuery = resultQuery;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
        this.currentPrincipal = currentPrincipal;
    }

    /**
     * 读取 Run 结果。
     *
     * @param runId Run 标识
     */
    @GetMapping("/api/runs/{runId}/result")
    public ResponseEntity<ResultResponse> getResult(@PathVariable String runId) {
        // ⚠️ 租户来自已验签令牌，绝不从请求参数取
        TenantContext context = currentPrincipal.context();

        // ⚠️ 执行时授权（红线 9）
        authorization.requireAuthorized(
                context, AuthorizationService.Actions.RESULT_READ, runId);

        // ⚠️ 限流：复用 EVENT_QUERY 桶（与 G3 同属「只读轮询」类），
        //    不新增桶——避免改动既有限流配置的语义与配额基线。
        RateLimitPort.Decision limit = rateLimiter.check(
                context.tenantId(), RateLimitPort.Buckets.EVENT_QUERY);
        if (limit != RateLimitPort.Decision.ALLOWED) {
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        }

        ResultQueryPort.RunResult result = resultQuery.findResult(context.tenantId(), runId);
        if (result == null) {
            // ⚠️ 不存在与「不属于该租户」返回同一个 404：
            //    区分即构成存在性探测通道。
            log.info("Run 结果查询未命中: tenant={}, runId={}", context.tenantId(), runId);
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "资源不存在");
        }

        return ResponseEntity.ok(new ResultResponse(
                result.runId(),
                result.state(),
                result.completedAt(),
                result.errorCode(),
                result.explanation(),
                result.weaknesses().stream().map(WeaknessResponse::of).toList(),
                result.steps().stream().map(StepResponse::of).toList(),
                new ReconciliationResponse(
                        result.reconciliation().pending(),
                        result.reconciliation().operations().stream()
                                .map(UnknownOperationResponse::of).toList())));
    }

    /**
     * Run 结果响应。
     *
     * <p>⚠️ <b>字段名一律 camelCase</b>：Java record 组件名直接序列化为 camelCase，
     * 前端零转换取值（对齐 {@code RunStatusResponse} / {@code ConfigResponse}）。
     *
     * <p>⚠️ <b>不提供</b>：{@code inputPayload}（学生提交的题目与解答）、
     * {@code tenantId}、{@code errorMessage}、任何教材引用/页码/出处字段
     * （C5 硬红线，DEC-5 不做 RAG）。
     *
     * @param runId          Run 标识
     * @param state          Run 状态（{@code run_state} 枚举）
     * @param completedAt    完成时刻；<b>非终态为 {@code null}</b>（不可退化为 EPOCH）
     * @param errorCode      失败原因<b>码</b>；<b>不是</b>错误原文
     * @param explanation    讲解文本；<b>仅成功时有值</b>
     * @param weaknesses     错因与知识点；<b>空即空列表</b>（不可为 null）
     * @param steps          解题步骤；<b>空即空列表</b>（不可为 null）
     * @param reconciliation 待核对分区；<b>恒存在</b>
     */
    public record ResultResponse(
            @NonNull String runId,
            @NonNull String state,
            @Nullable Instant completedAt,
            @Nullable String errorCode,
            @Nullable String explanation,
            @NonNull List<WeaknessResponse> weaknesses,
            @NonNull List<StepResponse> steps,
            @NonNull ReconciliationResponse reconciliation
    ) {
        public ResultResponse {
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
            steps = steps == null ? List.of() : List.copyOf(steps);
            reconciliation = reconciliation == null
                    ? new ReconciliationResponse(false, List.of())
                    : reconciliation;
        }
    }

    /**
     * 错因与知识点。
     *
     * @param knowledgePoint 知识点
     * @param errorType      错误类型
     * @param confidence     置信度；<b>仅供参考，不参与任何判定</b>
     */
    public record WeaknessResponse(
            @NonNull String knowledgePoint,
            @NonNull String errorType,
            @Nullable BigDecimal confidence
    ) {
        static WeaknessResponse of(ResultQueryPort.Weakness w) {
            return new WeaknessResponse(w.knowledgePoint(), w.errorType(), w.confidence());
        }
    }

    /**
     * 解题步骤。
     *
     * @param number   步骤序号
     * @param state    步骤状态
     * @param stepType 步骤类型
     * @param output   步骤产出载荷的原始 JSON 文本；
     *                 ⚠️ <b>当前无生产写入方</b>（P4-D10 L-2），故其形状尚未定义
     */
    public record StepResponse(
            int number,
            @NonNull String state,
            @NonNull String stepType,
            @Nullable String output
    ) {
        static StepResponse of(ResultQueryPort.Step s) {
            return new StepResponse(s.number(), s.state(), s.stepType(), s.output());
        }
    }

    /**
     * 待核对分区（P0-D00 §3.4 硬要求）。
     *
     * <p>⚠️ <b>恒存在</b>：未决 UNKNOWN 必须<b>独立暴露</b>，
     * 不得「有才显示」——否则「无未决」与「未实现」无法区分。
     *
     * @param pending    是否存在未决操作
     * @param operations 未决操作明细
     */
    public record ReconciliationResponse(
            boolean pending,
            @NonNull List<UnknownOperationResponse> operations
    ) {
        public ReconciliationResponse {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /**
     * 未决工具操作。
     *
     * @param operationId 操作标识
     * @param toolName    工具名
     * @param state       操作状态（当前仅 {@code UNKNOWN}）
     */
    public record UnknownOperationResponse(
            @NonNull String operationId,
            @NonNull String toolName,
            @NonNull String state
    ) {
        static UnknownOperationResponse of(ResultQueryPort.UnknownOperation o) {
            return new UnknownOperationResponse(o.operationId(), o.toolName(), o.state());
        }
    }
}
