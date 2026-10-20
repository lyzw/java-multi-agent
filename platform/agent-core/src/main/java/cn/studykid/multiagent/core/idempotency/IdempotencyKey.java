package cn.studykid.multiagent.core.idempotency;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 幂等键（ADR-003）。
 *
 * <p><b>键 = 命名空间 + 具体键值</b>。命名空间不可省略，原因：
 * 不同用途的键若混在一起，`run:xxx` 与 `tool:xxx` 可能在同一租户内撞值，
 * 导致「用工具键去查 Run」的错误复用。
 *
 * <p><b>首期键定义</b>（P0-D00 §3.2）：
 * <table border="1">
 *   <caption>幂等键构造规则</caption>
 *   <tr><th>用途</th><th>命名空间</th><th>键值构造</th></tr>
 *   <tr><td>创建 Run</td><td>{@code run.create}</td>
 *       <td>调用方提供（建议 UUID）</td></tr>
 *   <tr><td>{@code record_weakness}</td><td>{@code tool.record_weakness}</td>
 *       <td>{@code student_id + run_id + knowledge_point}</td></tr>
 *   <tr><td>其他工具调用</td><td>{@code tool.invoke}（P4-D08）</td>
 *       <td>{@code run_id + tool_name + call_id}</td></tr>
 * </table>
 *
 * <p><b>分隔符用 {@code '/'} 而非 {@code '|'}</b>：管道符在日志、SQL LIKE、
 * 正则表达式中语义复杂，容易引发转义问题。斜杠更安全，且天然可读。
 *
 * <p>⚠️ <b>键值不得包含学生可识别信息</b>（P0-D00 §4 白名单）：
 * {@code student_id} 必须是随机标识，不能是姓名/学号。
 */
public record IdempotencyKey(@NonNull String namespace, @NonNull String keyValue) {

    /** 分隔符。刻意避开管道符——见类注释。 */
    public static final String SEPARATOR = "/";

    /** 创建 Run 的命名空间。 */
    public static final String NS_RUN_CREATE = "run.create";

    /** 记录薄弱点的命名空间。 */
    public static final String NS_TOOL_RECORD_WEAKNESS = "tool.record_weakness";

    /**
     * 工具调用的命名空间（P4-D08）。
     *
     * <p>⚠️ <b>刻意不与 {@link #NS_RUN_CREATE} 复用</b>：
     * 类注释已说明「命名空间不可省略——不同用途的键若混在一起，
     * {@code run:xxx} 与 {@code tool:xxx} 可能在同一租户内撞值」。
     * 创建 Run 的键落在 {@code run} 表，工具调用的键落在 {@code tool_operation} 表，
     * 两表约束彼此独立，但<b>共用命名空间会让日志与排障无法区分来源</b>，
     * 并违背 ADR-003 的隔离意图。
     */
    public static final String NS_TOOL_INVOKE = "tool.invoke";

    /** 默认保留期：24 小时（ADR-003）。 */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    public IdempotencyKey {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("幂等键命名空间不能为空");
        }
        if (keyValue == null || keyValue.isBlank()) {
            throw new IllegalArgumentException("幂等键值不能为空");
        }
        if (namespace.contains(SEPARATOR)) {
            throw new IllegalArgumentException("命名空间不得包含分隔符 '" + SEPARATOR + "'");
        }
        // 注意：键值<b>允许</b>包含分隔符——复合键（如 record_weakness 的
        // studentId + runId + knowledgePoint）必然要用它拼接各组成部分。
        // 各组成部分的合法性由工厂方法逐个校验，避免拼接后才发现问题。
    }

    /** 创建 Run 的键。 */
    public static @NonNull IdempotencyKey forRunCreate(@NonNull String callerKey) {
        requirePart(callerKey, "callerKey");
        return new IdempotencyKey(NS_RUN_CREATE, callerKey);
    }

    /**
     * {@code record_weakness} 的键。
     *
     * <p>参数取自 DEC-6 白名单（随机 studentId + 薄弱知识点），
     * <b>不含姓名/学校/班级</b>。
     *
     * <p>各组成部分<b>均不得含分隔符</b>，否则拼接后无法唯一分解，
     * 会导致 {@code (a/b, c)} 与 {@code (a, b/c)} 产生相同的键——那将使
     * 不同请求错误地复用同一逻辑操作。
     *
     * @param studentId      随机学生标识（不得为可识别信息）
     * @param runId          所属 Run
     * @param knowledgePoint 薄弱知识点
     */
    public static @NonNull IdempotencyKey forRecordWeakness(@NonNull String studentId,
                                                            @NonNull String runId,
                                                            @NonNull String knowledgePoint) {
        requirePart(studentId, "studentId");
        requirePart(runId, "runId");
        requirePart(knowledgePoint, "knowledgePoint");
        return new IdempotencyKey(NS_TOOL_RECORD_WEAKNESS,
                studentId + SEPARATOR + runId + SEPARATOR + knowledgePoint);
    }

    /**
     * 工具调用的键（P4-D08）。
     *
     * <p><b>键 = {@code run_id + tool_name + call_id}</b>。
     *
     * <p>⚠️ <b>runId 必须参与</b>：{@code tool_operation} 的唯一约束是
     * {@code UNIQUE (tenant_id, idempotency_key)}——<b>租户级</b>，不含 run 维度。
     * 若键中缺 runId，则<b>同一租户下不同 Run 的同名工具 + 同 callId 会撞键</b>，
     * 而账本命中重复键时**复用既有 operation**（不新建），
     * 于是新 Run 的调用被记到旧 Run 的 operation 上——对账链路与副作用语义同时破裂。
     *
     * @param runId    所属 Run
     * @param toolName 工具名
     * @param callId   模型返回的调用标识（缺失时由调用方填占位值）
     */
    public static @NonNull IdempotencyKey forToolInvoke(@NonNull String runId,
                                                        @NonNull String toolName,
                                                        @NonNull String callId) {
        requirePart(runId, "runId");
        requirePart(toolName, "toolName");
        requirePart(callId, "callId");
        return new IdempotencyKey(NS_TOOL_INVOKE,
                runId + SEPARATOR + toolName + SEPARATOR + callId);
    }

    /** 校验单个组成部分：非空且不含分隔符。 */
    private static void requirePart(@NonNull String part, @NonNull String name) {
        if (part.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (part.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    name + " 不得包含分隔符 '" + SEPARATOR + "'，否则复合键无法唯一分解: " + part);
        }
    }

    /** 唯一约束比对用的复合形式：{@code namespace:keyValue}。 */
    public @NonNull String composite() {
        return namespace + ":" + keyValue;
    }

    /** @return 该键的过期时刻。 */
    public @NonNull Instant expiresAt(@NonNull Instant createdAt) {
        return createdAt.plus(DEFAULT_RETENTION);
    }

    @Override
    public String toString() {
        return composite();
    }
}