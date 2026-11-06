package cn.studykid.multiagent.infrastructure.tool;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.core.idempotency.RequestDigest;
import cn.studykid.multiagent.ports.tool.ToolExecutionException;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * {@code record_weakness} 工具（P2-D04 §4 / FR-008 / D-007）。
 *
 * <p><b>首期唯一的有副作用工具</b>，承载红线 4/5/7 的实证验证。
 * 没有它，最危险的协议就只在玩具环境里验证过。
 *
 * <p><b>⚠️ 本类的异常分类决定是否重复写入</b>：
 * 抛 {@code definitelyFailed} = 无副作用，可重试；
 * 抛 {@code unknownResult} = <b>可能已写入，禁止重试</b>。
 * 混淆二者 = <b>Sev-0</b>。
 */
public class RecordWeaknessTool implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(RecordWeaknessTool.class);

    /** D-007 契约：errorType 用枚举而非自由文本。 */
    public static final Set<String> ERROR_TYPES = Set.of(
            "概念不清", "公式记错", "符号错误", "计算失误", "审题偏差", "步骤缺失");

    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "required": ["studentId", "knowledgePoint", "errorType"],
              "properties": {
                "studentId": { "type": "string", "pattern": "^stu_[a-z0-9]{16}$" },
                "knowledgePoint": { "type": "string", "minLength": 1, "maxLength": 100 },
                "errorType": {
                  "type": "string",
                  "enum": ["概念不清", "公式记错", "符号错误", "计算失误", "审题偏差", "步骤缺失"]
                },
                "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
              },
              "additionalProperties": false
            }
            """;

    private static final java.util.regex.Pattern STUDENT_ID =
            java.util.regex.Pattern.compile("^stu_[a-z0-9]{16}$");

    private static final Set<String> ALLOWED_KEYS =
            Set.of("studentId", "knowledgePoint", "errorType", "confidence");

    private final StudentDataRepository repository;

    public RecordWeaknessTool(@NonNull StudentDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return "record_weakness";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        // DEC-11：WRITE_PROFILE，审批 OPTIONAL（当前配置不需审批）
        return ToolRiskLevel.WRITE_PROFILE;
    }

    @Override
    public void validate(Map<String, Object> arguments) {
        for (String key : arguments.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("不被接受的参数: " + key);
            }
        }
        String studentId = requireString(arguments, "studentId");
        if (!STUDENT_ID.matcher(studentId).matches()) {
            throw new IllegalArgumentException("studentId 格式非法");
        }
        String knowledgePoint = requireString(arguments, "knowledgePoint");
        if (knowledgePoint.length() > 100) {
            throw new IllegalArgumentException("knowledgePoint 长度超限");
        }
        String errorType = requireString(arguments, "errorType");
        if (!ERROR_TYPES.contains(errorType)) {
            // ⚠️ 自由文本会让知识点无限发散，且成为 Prompt 注入载体
            throw new IllegalArgumentException("errorType 不在允许枚举内");
        }
        Object confidence = arguments.get("confidence");
        if (confidence != null) {
            double c = ((Number) confidence).doubleValue();
            if (c < 0 || c > 1) {
                throw new IllegalArgumentException("confidence 越界");
            }
        }
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return String.valueOf(v);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String studentId = String.valueOf(arguments.get("studentId"));
        String knowledgePoint = String.valueOf(arguments.get("knowledgePoint"));
        String errorType = String.valueOf(arguments.get("errorType"));
        Double confidence = arguments.get("confidence") == null ? null
                : ((Number) arguments.get("confidence")).doubleValue();

        // ⚠️ 摘要只对**工具参数**计算，不含 runId/tenantId。
        //    若把上下文算进去，同一逻辑操作在不同 Run 中摘要不同
        //    → 幂等失效 → 重复写入 = Sev-0
        RequestDigest digest = RequestDigest.of(arguments);

        try {
            StudentDataRepository.WriteOutcome outcome = repository.insertWeakness(
                    context.tenantId(), studentId, context.runId(),
                    knowledgePoint, errorType, confidence);

            String json = "{\"recorded\":true,\"weaknessId\":\"" + outcome.weaknessId()
                    + "\",\"replayed\":" + outcome.replayed() + "}";
            return ToolResult.of(json);

        } catch (StudentDataRepository.DuplicateWeaknessException e) {
            // 同键但内容不同 → 明确冲突，无副作用
            throw ToolExecutionException.definitelyFailed("幂等冲突：同一键已有不同内容的记录");

        } catch (IllegalStateException e) {
            // ⚠️⚠️ 关键判断：异常发生在**写操作**过程中，
            //    无法确定是否已写入 → 必须按 unknownResult 处理。
            //    若按 definitelyFailed 处理而实际已落库，重试即重复写入 = Sev-0
            //
            //    注：SQLException 已被 StudentDataRepository 包装为
            //    IllegalStateException，故此处只捕获后者。
            log.warn("薄弱点写入结果不确定，转对账: runId={}, err={}",
                    context.runId(), e.getMessage());
            throw ToolExecutionException.unknownResult(
                    "写入结果不确定，需对账: " + e.getMessage(), e);
        }
    }

    /**
     * 对账（P2-D04 §4.2）。
     *
     * <p>⚠️ 返回 {@code null} 表示**下游不可查**，调用方须转人工而非重试。
     * 本实现能确定结果，故不会返回 null —— 查得到返回 ID，查不到返回空。
     */
    @Override
    public ToolResult reconcile(ToolExecutionContext context, String idempotencyKey) {
        String[] parts = idempotencyKey.split("/");
        if (parts.length < 3) {
            // 键格式不符 → 无法对账
            // ⚠️ 返回 null 而非抛异常：null = 不可查 → 转人工，**不重试**
            log.warn("幂等键格式不符，无法对账，转人工: key={}", idempotencyKey);
            return null;
        }
        Optional<String> found = repository.reconcileWeakness(
                context.tenantId(), parts[0], parts[1], parts[2]);
        if (found.isEmpty()) {
            // 确认不存在 → RECONCILED_ABSENT（调用方据此处置）
            return ToolResult.of("{\"reconciled\":\"ABSENT\"}");
        }
        return ToolResult.of("{\"reconciled\":\"SUCCEEDED\",\"weaknessId\":\""
                + found.get() + "\"}");
    }
}