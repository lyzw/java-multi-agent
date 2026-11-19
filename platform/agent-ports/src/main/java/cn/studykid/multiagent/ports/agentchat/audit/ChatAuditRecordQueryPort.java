package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 对话审计记录「查询」端口（平台效果评估/审计角色读，跨租户）。
 *
 * <p><b>⚠️ 与录制链路彻底分开</b>：录制走 {@link ChatAuditSinkPort}/{@link ChatAuditWriterPort}
 * （应用角色 + TenantSessionBinder + RLS 只写当前租户）；本端口是<b>只读</b>侧，
 * 服务于 admin-console「对话审计记录」查询页，需跨租户还原某次请求。
 *
 * <p><b>⚠️ 为什么另起端口而不复用写入仓储</b>：
 * <ol>
 *   <li>写入仓储 {@code JdbcChatAuditSink} 挂主数据源（app_agent，NOBYPASSRLS），
 *       受 FORCE RLS 限制<b>只能读当前绑定租户</b>——平台角色没有可绑的租户，查不到全量；</li>
 *   <li>跨租户读必须走 BYPASSRLS 的 platform_ops 数据源（组合根注入），
 *       把两种凭据混进同一个类会让「读」的路径也拿到写权限，违背最小权限；</li>
 *   <li>列表查询只读摘要列、不拉整个 {@code envelope}（同 {@code JdbcRunListQueryRepository}
 *       的纪律：原文只在详情端点按 audit_id 单条取）。</li>
 * </ol>
 *
 * <p><b>⚠️ 两域合并</b>：{@code chat_audit_record}（已归属，含原文）与
 * {@code chat_audit_unattributed}（建连前失败，仅元数据）在列表层统一成
 * {@link Summary}，以 {@code source} 区分；时间线按 {@code startedAt DESC} 合并排序。
 * 详情只能按 {@code (source, auditId)} 单条取——两张表的 audit_id 可能重复
 * （虽概率极低），不能用裸 audit_id 定位。
 */
public interface ChatAuditRecordQueryPort {

    /** 记录来源：已归属（含原文）/ 建连前失败（仅元数据）。 */
    enum Source {
        RECORD, UNATTRIBUTED;

        public static @NonNull Source parse(@Nullable String raw) {
            if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw)) {
                return Source.RECORD;
            }
            return Source.valueOf(raw.trim().toUpperCase());
        }
    }

    /**
     * 列表查询（游标分页，跨租户）。
     *
     * @throws IllegalArgumentException 游标损坏 / 参数非法（→ 400）
     */
    @NonNull Page<Summary> list(@NonNull Query query);

    /**
     * 单条详情：返回完整信封 JSON（{@code envelope} 或 {@code metadata} 的原始文本），
     * 由 API 层反序列化后回显。<b>找不到返回 null</b>（Controller 转 404）。
     */
    @Nullable Detail detail(@NonNull Source source, @NonNull String auditId);

    /**
     * 查询条件。
     *
     * @param source        来源；{@code null}/ALL = 两域合并（默认 RECORD，见 Controller）
     * @param tenantId      租户精确匹配（uuid 文本，调用方已校验）；unattributed 行 tenantId 为 nil UUID
     * @param endpoint      端点精确匹配
     * @param terminalState 终态精确匹配（RECORD 域有效）
     * @param from          起始时间（含）
     * @param to            截止时间（含）
     * @param cursor        游标；null/空白 = 第一页
     * @param limit         页大小（调用方已 clamp）
     */
    record Query(
            @Nullable Source source,
            @Nullable String tenantId,
            @Nullable String endpoint,
            @Nullable String terminalState,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable String cursor,
            int limit) {
    }

    /** 列表摘要（不含 envelope 原文，避免列表页加载敏感全文）。 */
    record Summary(
            @NonNull String auditId,
            @NonNull String traceId,
            @NonNull String source,
            @Nullable String tenantId,
            /** RECORD 域为 PLATFORM/TENANT；UNATTRIBUTED 建连前身份未解析，为 null。 */
            @Nullable String domain,
            @Nullable String principalId,
            @NonNull String endpoint,
            @NonNull String startedAt,
            @Nullable String completedAt,
            long durationMs,
            @NonNull String terminalState,
            @NonNull String httpMethod,
            int httpStatus,
            /** 用户问题预览（envelope.request.message，截断，仅摘要）。 */
            @Nullable String messagePreview,
            /** 拒绝原因（仅 UNATTRIBUTED 有值）。 */
            @Nullable String rejectionReason) {
    }

    /**
     * 详情（完整原文）。
     *
     * @param payloadJson 信封原文 JSON 文本：RECORD = {@code envelope::text}，
     *                    UNATTRIBUTED = {@code metadata::text}
     */
    record Detail(
            @NonNull String source,
            @NonNull String auditId,
            @NonNull String traceId,
            @Nullable String tenantId,
            @Nullable String domain,
            @Nullable String principalId,
            @NonNull String endpoint,
            @NonNull String startedAt,
            @Nullable String completedAt,
            long durationMs,
            @NonNull String terminalState,
            @NonNull String httpMethod,
            int httpStatus,
            long configVersion,
            @Nullable String rejectionReason,
            @NonNull String payloadJson) {
    }
}
