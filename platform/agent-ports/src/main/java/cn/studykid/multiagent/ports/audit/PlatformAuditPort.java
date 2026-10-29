package cn.studykid.multiagent.ports.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台审计端口（重规划 §5.3 / §12）。
 *
 * <p>平台管理操作审计与租户业务审计<b>分域保存</b>：本端口只写/查
 * {@code platform.platform_audit_event}（无 {@code tenant_id}），
 * 与租户侧 {@code audit_record} 物理隔离。两者通过 {@code correlation_id}
 * 在必要时关联调查，但绝不在同一张表里混写。
 *
 * <p>审计记录<b>仅追加</b>：实现须保证 {@code INSERT} 后不可 {@code UPDATE/DELETE}
 * （应用层不提供修改/删除端点），满足不可抵赖要求。
 */
public interface PlatformAuditPort {

    /** 写入一条平台审计事件（仅追加）。 */
    void record(@NonNull PlatformAuditEvent event);

    /** 按条件查询平台审计事件（游标分页）。 */
    @NonNull
    List<PlatformAuditEvent> query(@NonNull PlatformAuditQuery query);

    /**
     * 平台审计事件（不可变表示）。
     *
     * <p><b>组件顺序与 {@code platform.platform_audit_event} 的列顺序一致</b>：
     * JDBC 实现按位置绑定占位符，让两者同序可以避免「加一列就要重排全部构造点」的漂移，
     * 也让带外 psql 里读到的行序与代码里的参数序可互相核对。
     *
     * @param ticketScope 治理票据声明的<b>档位</b>（{@code PLATFORM} / {@code TENANT} / {@code ALL}，
     *                    与 RAG 侧 {@code RagCorpusScope} 同一根轴；{@code null} = 本条审计与票据无关，
     *                    这是多数行的常态）。
     *                    ⚠️ <b>只准落这三个规范值</b>：被拒请求里的档位是调用方可控文本，
     *                    规范集之外一律写 {@code null} —— 库侧有 CHECK，
     *                    未校验文本会让一次审计写入抛 SQLException，
     *                    把「503 拒绝」变成「500 故障」，即闸门自己制造事故（U-8 / 方案 §5 A4）。
     */
    record PlatformAuditEvent(
            @NonNull UUID id,
            @NonNull String actorId,
            @NonNull String actorType,
            @NonNull String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @NonNull String decision,
            @Nullable String reasonCode,
            @Nullable UUID correlationId,
            @Nullable String ip,
            @NonNull Instant occurredAt,
            @Nullable String ticketScope
    ) {
    }

    /** 平台审计查询条件。 */
    record PlatformAuditQuery(
            @Nullable String actorId,
            @Nullable String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable Instant from,
            @Nullable Instant to,
            int limit,
            @Nullable String cursor
    ) {
        public static @NonNull PlatformAuditQuery of(int limit) {
            return new PlatformAuditQuery(null, null, null, null, null, null,
                    Math.max(1, Math.min(limit, 200)), null);
        }
    }
}
