package cn.studykid.multiagent.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.mapper.ChatAuditRecordQueryMapper;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.persistence.Page;

/** ChatAuditRecordQueryPort MyBatis 实现（RECORD ∪ UNATTRIBUTED 联合查询）。 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisChatAuditRecordQueryRepository implements ChatAuditRecordQueryPort {

    private final ChatAuditRecordQueryMapper mapper;

    public MybatisChatAuditRecordQueryRepository(@NonNull ChatAuditRecordQueryMapper mapper,
                                                   @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
    }

    @Override
    public @NonNull Page<Summary> list(@NonNull Query q) {
        boolean includeRecord = q.source() == null || q.source() == Source.RECORD;
        boolean includeUnattributed = q.source() == null || q.source() == Source.UNATTRIBUTED;
        int limit = Math.max(1, Math.min(q.limit(), 200));
        List<ChatAuditRecordQueryMapper.SummaryRow> rows = mapper.list(q.tenantId(), q.endpoint(),
                q.terminalState(), q.from(), q.to(), includeRecord, includeUnattributed, limit + 1);
        List<Summary> items = rows.stream().limit(limit).map(this::toSummary).toList();
        boolean hasMore = rows.size() > limit;
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Summary last = items.get(items.size() - 1);
            nextCursor = last.auditId();
        }
        return new Page<>(items, nextCursor);
    }

    @Override
    public @Nullable Detail detail(@NonNull Source source, @NonNull String auditId) {
        ChatAuditRecordQueryMapper.DetailRow r = source == Source.UNATTRIBUTED
                ? mapper.detailUnattributed(auditId)
                : mapper.detailRecord(auditId);
        if (r == null) {
            return null;
        }
        return new Detail(source.name(), r.auditId(), r.traceId(), r.tenantId(), r.domain(), r.principalId(),
                r.endpoint(), str(r.startedAt()), str(r.completedAt()),
                r.durationMs() == null ? 0L : r.durationMs(), r.terminalState(),
                r.httpMethod(), r.httpStatus() == null ? 0 : r.httpStatus(),
                r.configVersion() == null ? 0L : r.configVersion(), r.rejectionReason(), r.payloadJson());
    }

    private Summary toSummary(ChatAuditRecordQueryMapper.SummaryRow r) {
        return new Summary(r.auditId(), r.traceId(), r.source(), r.tenantId(), r.domain(),
                r.principalId(), r.endpoint(), str(r.startedAt()), str(r.completedAt()),
                r.durationMs() == null ? 0L : r.durationMs(), r.terminalState(),
                r.httpMethod(), r.httpStatus() == null ? 0 : r.httpStatus(),
                r.messagePreview(), r.rejectionReason());
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }
}
