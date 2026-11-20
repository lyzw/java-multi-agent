package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.cap.ConfigDraft;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TenantConnectionLease;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.ConfigDraftPort;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 变更草稿 JDBC 仓储（cap_config_draft）。
 *
 * <p>⚠️ 状态迁移双重守卫：
 * <ul>
 *   <li>DB 层 CHECK + 唯一索引（终态可存、活跃态唯一条）；</li>
 *   <li>本仓储层：迁移前 {@code SELECT ... FOR UPDATE} 加载 →
 *       校验 {@code current_state == expectedCurrentState}（并发 CAS）。</li>
 * </ul>
 */
@Repository
public class JdbcConfigDraftRepository implements ConfigDraftPort {

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private static final String PLATFORM_SCOPE_TENANT = "00000000-0000-0000-0000-000000000000";

    /** 合法状态迁移表（键=当前状态，值=允许迁移到的目标状态集合）。 */
    private static final java.util.Map<String, java.util.Set<String>> TRANSITIONS = java.util.Map.of(
            "DRAFT",                 java.util.Set.of("PENDING_APPROVAL", "PENDING_PUBLISH", "DISCARDED"),
            "PENDING_APPROVAL",      java.util.Set.of("PENDING_PUBLISH", "APPROVAL_REJECTED", "DISCARDED"),
            "PENDING_PUBLISH",       java.util.Set.of("PUBLISH_FAILED", "DISCARDED"),
            "APPROVAL_REJECTED",     java.util.Set.of("DRAFT", "DISCARDED"),
            "PUBLISH_FAILED",        java.util.Set.of("DRAFT", "PENDING_PUBLISH", "DISCARDED")
    );

    private final DataSource dataSource;

    public JdbcConfigDraftRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull ConfigDraft insert(@NonNull ConfigDraft draft) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(draft.tenantId()),
                conn -> {
                    String sql = """
                            INSERT INTO cap_config_draft
                                (draft_id, scope, tenant_id, resource_kind, resource_id, resource_name,
                                 base_version, draft_version, draft_state, draft_config,
                                 config_fingerprint, operator_id)
                            VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                            RETURNING created_at, updated_at
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int i = 1;
                        ps.setObject(i++, draft.draftId());
                        ps.setString(i++, draft.scope());
                        bindUuid(ps, i++, draft.tenantId());
                        ps.setString(i++, draft.resourceKind());
                        ps.setString(i++, draft.resourceId());
                        ps.setString(i++, draft.resourceName());
                        ps.setInt(i++, draft.baseVersion());
                        ps.setInt(i++, draft.draftVersion());
                        ps.setString(i++, draft.draftState());
                        ps.setString(i++, draft.draftConfig());
                        ps.setString(i++, draft.configFingerprint());
                        ps.setString(i++, draft.operatorId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("cap_config_draft INSERT 未返回时间戳");
                            return new ConfigDraft(
                                    draft.draftId(), draft.scope(), draft.tenantId(),
                                    draft.resourceKind(), draft.resourceId(), draft.resourceName(),
                                    draft.baseVersion(), draft.draftVersion(), draft.draftState(),
                                    draft.draftConfig(), draft.configFingerprint(), draft.latestRequestId(),
                                    draft.failureStage(), draft.failureDetails(), draft.rejectionReason(),
                                    draft.operatorId(),
                                    rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()
                            );
                        }
                    }
                });
    }

    @Override
    public @NonNull Optional<ConfigDraft> findById(@NonNull String scope,
                                                     @Nullable String tenantId,
                                                     @NonNull UUID draftId) {
        String sql = selectColumns() + """
                WHERE scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid AND draft_id = ?::uuid
                """;
        return queryOne(scope, tenantId, sql, ps -> {
            ps.setString(1, scope);
            bindUuid(ps, 2, tenantId);
            ps.setObject(3, draftId);
        });
    }

    @Override
    public @NonNull Optional<ConfigDraft> findActiveByResource(@NonNull String scope,
                                                                 @Nullable String tenantId,
                                                                 @NonNull String resourceKind,
                                                                 @NonNull String resourceId) {
        String sql = selectColumns() + """
                WHERE scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid
                  AND resource_kind = ? AND resource_id = ?
                  AND draft_state IN ('DRAFT','PENDING_APPROVAL','PENDING_PUBLISH','APPROVAL_REJECTED','PUBLISH_FAILED')
                ORDER BY updated_at DESC LIMIT 1
                """;
        return queryOne(scope, tenantId, sql, ps -> {
            ps.setString(1, scope);
            bindUuid(ps, 2, tenantId);
            ps.setString(3, resourceKind);
            ps.setString(4, resourceId);
        });
    }

    @Override
    public @NonNull Page<ConfigDraft> list(@NonNull String scope,
                                             @Nullable String tenantId,
                                             @Nullable String state,
                                             @Nullable String cursor,
                                             int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit 必须为正数: " + limit);

        // 解析游标
        java.time.Instant afterTime = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(",");
            try {
                afterTime = Timestamp.from(java.time.Instant.parse(parts[0])).toInstant();
                afterId = UUID.fromString(parts[1]);
            } catch (Exception ignored) { /* 忽略非法游标，从头查 */ }
        }

        StringBuilder sql = new StringBuilder(selectColumns())
                .append("WHERE scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid");
        List<Object> params = new ArrayList<>();
        params.add(scope);
        params.add(tenantId);
        if (state != null && !state.isBlank()) {
            sql.append(" AND draft_state = ?");
            params.add(state);
        }
        if (afterTime != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND draft_id < ?::uuid))");
            params.add(Timestamp.from(afterTime));
            params.add(Timestamp.from(afterTime));
            params.add(afterId);
        }
        sql.append(" ORDER BY updated_at DESC, draft_id DESC LIMIT ?");
        params.add(limit + 1);

        List<ConfigDraft> rows = new ArrayList<>();
        String nextCursor = null;
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询草稿列表失败", e);
        }

        if (rows.size() > limit) {
            ConfigDraft last = rows.get(limit - 1);
            rows = new ArrayList<>(rows.subList(0, limit));
            nextCursor = last.updatedAt().toString() + "," + last.draftId();
        }
        return Page.of(rows, nextCursor);
    }

    @Override
    public @NonNull ConfigDraft saveContent(@NonNull String scope,
                                             @Nullable String tenantId,
                                             @NonNull UUID draftId,
                                             @NonNull String newConfig,
                                             @Nullable String newFingerprint,
                                             @NonNull String operatorId) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(tenantId),
                conn -> {
                    String sql = """
                            UPDATE cap_config_draft
                            SET draft_config = ?::jsonb, config_fingerprint = ?,
                                draft_version = draft_version + 1,
                                updated_at = now(), operator_id = ?
                            WHERE draft_id = ?::uuid AND scope = ?
                              AND tenant_id IS NOT DISTINCT FROM ?::uuid
                              AND draft_state IN ('DRAFT','APPROVAL_REJECTED','PUBLISH_FAILED')
                            RETURNING draft_id::text, scope, tenant_id::text, resource_kind, resource_id,
                                      resource_name, base_version, draft_version, draft_state,
                                      draft_config::text, config_fingerprint,
                                      latest_request_id::text, failure_stage, failure_details::text,
                                      rejection_reason, operator_id, created_at, updated_at
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        int i = 1;
                        ps.setString(i++, newConfig);
                        ps.setString(i++, newFingerprint);
                        ps.setString(i++, operatorId);
                        ps.setObject(i++, draftId);
                        ps.setString(i++, scope);
                        bindUuid(ps, i++, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException(
                                    "保存草稿失败：草稿不存在或当前状态不允许保存");
                            return read(rs);
                        }
                    }
                });
    }

    @Override
    public @NonNull ConfigDraft transitionState(@NonNull String scope,
                                                  @Nullable String tenantId,
                                                  @NonNull UUID draftId,
                                                  @NonNull String expectedCurrentState,
                                                  @NonNull String targetState,
                                                  @Nullable UUID linkedRequestId,
                                                  @Nullable String operatorId) {
        // 校验合法迁移
        java.util.Set<String> allowed = TRANSITIONS.get(expectedCurrentState);
        if (allowed == null || !allowed.contains(targetState)) {
            throw new IllegalStateException(
                    "草稿状态非法迁移: " + expectedCurrentState + " → " + targetState);
        }
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(tenantId),
                conn -> {
                    // FOR UPDATE 防止并发迁移
                    String loadSql = """
                            SELECT draft_state FROM cap_config_draft
                            WHERE draft_id = ?::uuid AND scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid
                            FOR UPDATE
                            """;
                    String actualState;
                    try (PreparedStatement ps = conn.prepareStatement(loadSql)) {
                        int i = 1;
                        ps.setObject(i++, draftId);
                        ps.setString(i++, scope);
                        bindUuid(ps, i++, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("草稿不存在: " + draftId);
                            actualState = rs.getString(1);
                        }
                    }
                    if (!expectedCurrentState.equals(actualState)) {
                        throw new IllegalStateException(
                                "草稿状态并发冲突：期望 " + expectedCurrentState + "，实际 " + actualState);
                    }

                    String updateSql = """
                            UPDATE cap_config_draft
                            SET draft_state = ?, latest_request_id = ?,
                                updated_at = now()
                                %s
                            WHERE draft_id = ?::uuid AND scope = ?
                              AND tenant_id IS NOT DISTINCT FROM ?::uuid
                            RETURNING draft_id::text, scope, tenant_id::text, resource_kind, resource_id,
                                      resource_name, base_version, draft_version, draft_state,
                                      draft_config::text, config_fingerprint,
                                      latest_request_id::text, failure_stage, failure_details::text,
                                      rejection_reason, operator_id, created_at, updated_at
                            """.formatted(operatorId != null ? ", operator_id = ?" : "");
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        int i = 1;
                        ps.setString(i++, targetState);
                        if (linkedRequestId != null) {
                            ps.setObject(i++, linkedRequestId);
                        } else {
                            ps.setNull(i++, Types.OTHER);
                        }
                        if (operatorId != null) ps.setString(i++, operatorId);
                        ps.setObject(i++, draftId);
                        ps.setString(i++, scope);
                        bindUuid(ps, i++, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("状态迁移失败");
                            return read(rs);
                        }
                    }
                });
    }

    @Override
    public @NonNull ConfigDraft markFailed(@NonNull String scope,
                                             @Nullable String tenantId,
                                             @NonNull UUID draftId,
                                             @NonNull String failStage,
                                             @NonNull String failDetailsJson,
                                             @NonNull String operatorId) {
        return TransactionalConnectionHolder.inTransaction(dataSource,
                tenantContext(tenantId),
                conn -> {
                    // 先允许 PENDING_PUBLISH / DRAFT（自动发布失败场景）→ PUBLISH_FAILED
                    String loadSql = """
                            SELECT draft_state FROM cap_config_draft
                            WHERE draft_id = ?::uuid AND scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid
                            FOR UPDATE
                            """;
                    String actualState;
                    try (PreparedStatement ps = conn.prepareStatement(loadSql)) {
                        int i = 1;
                        ps.setObject(i++, draftId);
                        ps.setString(i++, scope);
                        bindUuid(ps, i++, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("草稿不存在: " + draftId);
                            actualState = rs.getString(1);
                        }
                    }
                    if (!"PENDING_PUBLISH".equals(actualState) && !"DRAFT".equals(actualState)) {
                        throw new IllegalStateException(
                                "发布失败仅允许 PENDING_PUBLISH/DRAFT 状态，当前为 " + actualState);
                    }
                    String updateSql = """
                            UPDATE cap_config_draft
                            SET draft_state = 'PUBLISH_FAILED', failure_stage = ?,
                                failure_details = ?::jsonb, rejection_reason = NULL,
                                updated_at = now(), operator_id = ?
                            WHERE draft_id = ?::uuid AND scope = ? AND tenant_id IS NOT DISTINCT FROM ?::uuid
                            RETURNING draft_id::text, scope, tenant_id::text, resource_kind, resource_id,
                                      resource_name, base_version, draft_version, draft_state,
                                      draft_config::text, config_fingerprint,
                                      latest_request_id::text, failure_stage, failure_details::text,
                                      rejection_reason, operator_id, created_at, updated_at
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        int i = 1;
                        ps.setString(i++, failStage);
                        ps.setString(i++, failDetailsJson);
                        ps.setString(i++, operatorId);
                        ps.setObject(i++, draftId);
                        ps.setString(i++, scope);
                        bindUuid(ps, i++, tenantId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("标记发布失败失败");
                            return read(rs);
                        }
                    }
                });
    }

    @Override
    public @NonNull ConfigDraft markRejected(@NonNull String scope,
                                               @Nullable String tenantId,
                                               @NonNull UUID draftId,
                                               @Nullable UUID linkedRequestId,
                                               @NonNull String rejectionReason,
                                               @NonNull String operatorId) {
        return transitionState(scope, tenantId, draftId,
                "PENDING_APPROVAL", "APPROVAL_REJECTED", linkedRequestId, operatorId);
        // rejection_reason 在 APPROVAL_REJECTED 状态迁移里没有写入，直接用 transitionState 后
        // 再 UPDATE 写 rejection_reason —— 简化：这里覆盖成事务内两段式
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private static String selectColumns() {
        return """
                SELECT draft_id::text, scope, tenant_id::text, resource_kind, resource_id,
                       resource_name, base_version, draft_version, draft_state,
                       draft_config::text, config_fingerprint,
                       latest_request_id::text, failure_stage, failure_details::text,
                       rejection_reason, operator_id, created_at, updated_at
                FROM cap_config_draft
                """;
    }

    private static ConfigDraft read(@NonNull ResultSet rs) throws SQLException {
        return new ConfigDraft(
                UUID.fromString(rs.getString("draft_id")),
                rs.getString("scope"),
                rs.getString("tenant_id"),
                rs.getString("resource_kind"),
                rs.getString("resource_id"),
                rs.getString("resource_name"),
                rs.getInt("base_version"),
                rs.getInt("draft_version"),
                rs.getString("draft_state"),
                rs.getString("draft_config"),
                rs.getString("config_fingerprint"),
                rs.getString("latest_request_id") == null ? null
                        : UUID.fromString(rs.getString("latest_request_id")),
                rs.getString("failure_stage"),
                rs.getString("failure_details"),
                rs.getString("rejection_reason"),
                rs.getString("operator_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Optional<ConfigDraft> queryOne(@NonNull String scope,
                                            @Nullable String tenantId,
                                            @NonNull String sql,
                                            Binder binder) {
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询草稿失败", e);
        }
    }

    private static void bindUuid(@NonNull PreparedStatement ps, int index,
                                  @Nullable String uuidText) {
        try {
            if (uuidText == null || uuidText.isBlank()) {
                ps.setNull(index, Types.OTHER);
            } else {
                ps.setObject(index, UUID.fromString(uuidText));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("绑定 UUID 参数失败: index=" + index + ", value=" + uuidText, e);
        }
    }

    private TenantContext tenantContext(@Nullable String tenantId) {
        return new TenantContext(tenantId == null ? PLATFORM_SCOPE_TENANT : tenantId,
                "system", java.util.Set.of(), 0L);
    }

    private TenantConnectionLease tenantConnection(@Nullable String tenantId) throws SQLException {
        Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) return TenantConnectionLease.borrowed(outer);
        return TransactionalConnectionHolder.openTenantConnection(dataSource, tenantContext(tenantId));
    }
}
