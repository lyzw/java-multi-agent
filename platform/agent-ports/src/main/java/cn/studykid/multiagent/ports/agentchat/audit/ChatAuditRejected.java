package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 建连前即被拒的对话请求（身份未成立，<b>只存元数据</b>）。
 *
 * <p><b>⚠️ 为什么这种记录不能进 chat_audit_record</b>：那张表带租户级 FORCE RLS，
 * 而 401（令牌无效）/ 400（参数非法）时根本没有可信租户可绑。硬写就要么绑一个
 * 编造的值（污染隔离边界），要么在有 FORCE RLS 时静默 0 行（记录直接丢失）。
 *
 * <p><b>⚠️ 为什么必须记</b>：决策 1 已确认范围含「建连前失败」——被拒的请求恰恰是
 * 效果评估里最需要的那部分（参数错误率、限流命中、越权尝试）。只记成功请求，
 * 等于把「用不起来」的那一段从数据里抹掉。
 *
 * <p><b>⚠️ 绝不存原文</b>：这一层拿到的 body 是<b>未通过身份校验</b>的输入，
 * 其归属未知；落原文即等于把任意匿名输入写进审计库。
 * {@link #metadata} 只允许 URI / query key / 状态码 / 耗时这类结构性事实。
 *
 * @param auditId         审计标识（= traceId）
 * @param traceId         链路标识
 * @param startedAt       请求进入时间
 * @param endpoint        命中的审计端点（已归一化，不含 query）
 * @param httpMethod      HTTP 方法
 * @param httpStatus      最终状态码
 * @param rejectionReason 拒绝原因（已脱敏的短句，可为空）
 * @param metadata        结构化元数据（只放键名与计数，不放值）
 */
public record ChatAuditRejected(
        @NonNull String auditId,
        @NonNull String traceId,
        @NonNull Instant startedAt,
        @NonNull String endpoint,
        @NonNull String httpMethod,
        int httpStatus,
        @Nullable String rejectionReason,
        @NonNull Map<String, String> metadata) implements ChatAuditRecord {
}
