package cn.studykid.multiagent.infrastructure.persistence.cap.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 补偿任务持久化对象（对应 {@code JdbcCompensationTaskRepository}）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与 core 的 {@code CapCompensationTask} record 混用；
 * 适配层 {@code MybatisCompensationTaskRepository} 负责 DO ↔ record 的转换。
 *
 * <p>表结构来自 {@code cap_compensation_task}（租户级 RLS 表，FORCE ROW LEVEL SECURITY）。
 *
 * <p>⚠️ 入队幂等靠唯一约束 {@code (tenant_id, kind, target_id)} + {@code ON CONFLICT DO NOTHING}，
 * <b>不靠先查后插</b>：先查后插存在 check-then-act 窗口，并发补偿会落两条任务，
 * 而补偿必须幂等（重复释放额度 = 额度凭空放大）。
 *
 * <p>⚠️ {@code kind} / {@code state} 是 text 列（DB CHECK 枚举白名单），按 String 读写，
 * 由适配层用 {@code valueOf} 转回枚举 —— 与旧 JDBC 的 {@code rs.getString(...)} 口径一致。
 */
@TableName("cap_compensation_task")
public class CompensationTaskDO {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("tenant_id")
    private String tenantId;

    /** 补偿类型（{@code CompensationKind#name()}）。 */
    @TableField("kind")
    private String kind;

    @TableField("target_id")
    private String targetId;

    @TableField("invocation_id")
    private @Nullable String invocationId;

    /** 补偿参数（JSON 文本；该列是 text，不是 jsonb，故无 ::jsonb cast）。 */
    @TableField("payload")
    private @Nullable String payload;

    /** 任务状态（{@code TaskState#name()}）。 */
    @TableField("state")
    private String state;

    @TableField("attempts")
    private int attempts;

    @TableField("last_error")
    private @Nullable String lastError;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    /** 下次重试时刻；{@code null} = 立即可重试（扫描条件 {@code next_retry_at IS NULL OR <= now()}）。 */
    @TableField("next_retry_at")
    private @Nullable Instant nextRetryAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public @Nullable String getInvocationId() { return invocationId; }
    public void setInvocationId(@Nullable String invocationId) { this.invocationId = invocationId; }
    public @Nullable String getPayload() { return payload; }
    public void setPayload(@Nullable String payload) { this.payload = payload; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public @Nullable String getLastError() { return lastError; }
    public void setLastError(@Nullable String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public @Nullable Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(@Nullable Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
}
