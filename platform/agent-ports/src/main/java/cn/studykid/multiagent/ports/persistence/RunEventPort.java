package cn.studykid.multiagent.ports.persistence;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * Run 事件**追加**端口（P4-D12 / G4）。
 *
 * <p><b>与 {@link RunEventQueryPort} 的分工</b>：后者是<b>读</b>（SSE 续传），
 * 本端口是<b>写</b>（Worker 记录执行过程）。
 *
 * <p>⚠️ <b>为什么此前生产里没有写入方</b>：
 * {@code run_event} 表<b>只有 {@code RUN_CREATED} 由 Run 创建事务写入</b>，
 * Worker 从不写该表 → 进度页时间线<b>永远只有一条</b>（FE-01 §P4 缺口 G4）。
 * 表建了 ≠ 表里有数据——与 P4-D08 的 R-1（工具未注册为 Bean）同构。
 *
 * <p>⚠️ <b>载荷白名单</b>：本端口写入的载荷字段**必须**限 SSE 白名单
 * （{@code state, attempt, stepType, toolName, operationState, errorCode, phase}）
 * 且**只允许标量**（String/Number/Boolean）。
 * 理由：SSE 是<b>白名单透传</b>，白名单外的字段**不会**出现在响应里
 * （P3-D10 §5.2 S-6，fail-closed）——但**写入端仍应自律**：
 * 一旦把 Prompt 原文或学生信息写进表里，它就**永久留在库里**，
 * 即便不外泄，也是数据治理事故（PIPL 未成年人数据最小化）。
 */
public interface RunEventPort {

    /**
     * 追加一条执行事件。
     *
     * @param tenantId 租户标识（RLS 绑定依据）
     * @param runId    Run 标识
     * @param eventType 事件类型（如 {@code RUN_STARTED}）
     * @param payload  载荷；<b>只允许白名单标量字段</b>
     */
    void append(@NonNull String tenantId,
                @NonNull String runId,
                @NonNull String eventType,
                @NonNull Map<String, Object> payload);

    /**
     * 事件类型常量。
     *
     * <p>⚠️ 集中定义，避免各处硬编码字符串拼错——
     * 拼错的事件类型**不会报错**，只是前端时间线上出现一条看不懂的记录。
     */
    final class Types {
        private Types() {
        }

        /** Worker 领取租约并开始执行。 */
        public static final String RUN_STARTED = "RUN_STARTED";
        /** 工具调用结束（含 UNKNOWN）。 */
        public static final String TOOL_INVOKED = "TOOL_INVOKED";
        /** 执行成功。 */
        public static final String RUN_SUCCEEDED = "RUN_SUCCEEDED";
        /** 执行失败。 */
        public static final String RUN_FAILED = "RUN_FAILED";
        /** 已取消。 */
        public static final String RUN_CANCELLED = "RUN_CANCELLED";
    }
}
