package cn.studykid.multiagent.infrastructure.tool;

import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * {@code get_student_profile} 工具（P2-D04 §3 / FR-008 / D-007）。
 *
 * <p><b>只读</b>，无副作用，天然幂等。
 */
public class GetStudentProfileTool implements ToolHandler {

    /** D-007 契约定义的输入 Schema。 */
    public static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "required": ["studentId"],
              "properties": {
                "studentId": {
                  "type": "string",
                  "pattern": "^stu_[a-z0-9]{16}$"
                }
              },
              "additionalProperties": false
            }
            """;

    /** studentId 格式：随机标识（DEC-6 禁止可识别信息）。 */
    private static final java.util.regex.Pattern STUDENT_ID =
            java.util.regex.Pattern.compile("^stu_[a-z0-9]{16}$");

    private final StudentDataRepository repository;

    public GetStudentProfileTool(@NonNull StudentDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public String toolName() {
        return "get_student_profile";
    }

    @Override
    public String inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ;
    }

    @Override
    public void validate(Map<String, Object> arguments) {
        // ⚠️ additionalProperties: false —— 多传字段必须拒绝。
        //    否则客户端传姓名、学校等被忽略的字段，看似无害实则掩盖越界输入
        for (String key : arguments.keySet()) {
            if (!"studentId".equals(key)) {
                throw new IllegalArgumentException("不被接受的参数: " + key);
            }
        }
        Object studentId = arguments.get("studentId");
        if (studentId == null) {
            throw new IllegalArgumentException("缺少必填参数 studentId");
        }
        if (!STUDENT_ID.matcher(String.valueOf(studentId)).matches()) {
            // ⚠️ 姓名「张三」这类会在这里被拒——格式不匹配
            throw new IllegalArgumentException("studentId 格式非法");
        }
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String studentId = String.valueOf(arguments.get("studentId"));
        StudentDataRepository.ProfileView profile =
                repository.loadProfile(context.tenantId(), studentId);

        // ⚠️ 学生不存在返回空画像而非报错：
        //    若不存在报错、存在返回数据，攻击者可据此枚举有效 studentId
        StringBuilder json = new StringBuilder("{");
        json.append("\"studentId\":\"").append(studentId).append("\"");

        if (profile.grade() != null) {
            json.append(",\"grade\":\"").append(profile.grade()).append("\"");
            json.append(",\"subject\":\"").append(profile.subject()).append("\"");
            json.append(",\"weaknesses\":[");
            boolean first = true;
            for (StudentDataRepository.WeaknessView w : profile.weaknesses()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"knowledgePoint\":\"").append(w.knowledgePoint())
                        .append("\",\"errorType\":\"").append(w.errorType()).append("\"");
                if (w.confidence() != null) {
                    json.append(",\"confidence\":").append(w.confidence());
                }
                json.append('}');
            }
            json.append(']');
        } else {
            json.append(",\"weaknesses\":[],\"notFound\":true");
        }
        json.append('}');

        return ToolResult.of(json.toString());
    }
}