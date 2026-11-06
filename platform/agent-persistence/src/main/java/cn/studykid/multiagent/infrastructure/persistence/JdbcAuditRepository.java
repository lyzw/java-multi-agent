package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.compliance.DataClassification.IdentifyingPattern;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.AuditPort;

/**
 * {@link AuditPort} 的 JDBC 实现（P3-D07 / R-1 / R-2 / R-3）。
 *
 * <p><b>三个不变量</b>：
 * <ol>
 *   <li><b>敏感信息拒写</b>（D-3）—— 写入前逐字段跑 {@link IdentifyingPattern} 检测，
 *       命中身份证/手机/学号/邮箱即<b>抛异常拒绝整条写入</b>（fail-closed）。
 *       审计「拒写」优于「写了敏感信息」（PIPL 未成年人数据）。</li>
 *   <li><b>关键审计 fail-closed / 访问日志 fail-open</b>（D-1）——
 *       {@link #recordRequired} 异常上抛；{@link #recordAccess} 捕获后仅 WARN。</li>
 *   <li><b>追加不可改</b>（R-3）—— DB 触发器 {@code trg_audit_record_append_only}
 *       拒绝一切 UPDATE/DELETE。本类<b>刻意不提供任何 update/delete 方法</b>，
 *       但真正的防线在 DB（直连 SQL 也绕不过）。</li>
 * </ol>
 *
 * <p><b>连接所有权</b>：全部经 {@link TransactionalConnectionHolder#inTransaction}
 * （P2-D05 铁律：禁止直接 {@code dataSource.getConnection()}）。
 */
public class JdbcAuditRepository implements AuditPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditRepository.class);

    private final DataSource dataSource;

    public JdbcAuditRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void recordRequired(@NonNull String tenantId,
                               @NonNull String actorId,
                               @NonNull String action,
                               @NonNull String resourceType,
                               @Nullable String resourceId,
                               @NonNull Map<String, Object> detail) {
        // ⚠️ 不捕获任何异常：关键审计失败必须让主流程中止（D-1）。
        insert(tenantId, actorId, action, resourceType, resourceId, detail);
        log.info("关键审计已记录: tenant={}, actor={}, action={}, resource={}/{}",
                tenantId, actorId, action, resourceType, resourceId);
    }

    @Override
    public void recordAccess(@NonNull String tenantId,
                             @NonNull String actorId,
                             @NonNull String action,
                             @NonNull String resourceType,
                             @Nullable String resourceId,
                             @NonNull Map<String, Object> detail) {
        // ⚠️ 访问日志失败只告警（D-1）：举证能力下降不应阻断用户读取。
        try {
            insert(tenantId, actorId, action, resourceType, resourceId, detail);
            log.info("访问日志已记录: tenant={}, actor={}, action={}, resource={}/{}",
                    tenantId, actorId, action, resourceType, resourceId);
        } catch (RuntimeException e) {
            // ⚠️ 包括「敏感信息检出」——访问日志拒写同样不阻断，
            //    但必须留下可见痕迹，否则 PIPL 违规会被静默。
            log.error("访问日志写入失败（不阻断业务，但需排查）: tenant={}, action={}, resource={}/{}",
                    tenantId, action, resourceType, resourceId, e);
        }
    }

    @Override
    public @NonNull List<AuditView> findByResource(@NonNull String tenantId,
                                                   @NonNull String resourceType,
                                                   @Nullable String resourceId) {
        return inTenantTx(tenantId, conn -> {
            // ⚠️ resource_id 可空：Run 级动作用 correlation_id 关联，
            //    此时按 resource_type 全量查。
            String sql = resourceId == null
                    ? """
                    SELECT audit_id::text, actor_id, action, resource_type, resource_id, created_at
                    FROM audit_record
                    WHERE tenant_id = ?::uuid AND resource_type = ?
                    ORDER BY created_at
                    """
                    : """
                    SELECT audit_id::text, actor_id, action, resource_type, resource_id, created_at
                    FROM audit_record
                    WHERE tenant_id = ?::uuid AND resource_type = ? AND resource_id = ?
                    ORDER BY created_at
                    """;
            List<AuditView> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, resourceType);
                if (resourceId != null) {
                    ps.setString(3, resourceId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp created = rs.getTimestamp("created_at");
                        out.add(new AuditView(
                                rs.getString("audit_id"),
                                rs.getString("actor_id"),
                                rs.getString("action"),
                                rs.getString("resource_type"),
                                rs.getString("resource_id"),
                                created.toInstant()));
                    }
                }
            }
            return out;
        });
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private void insert(String tenantId, String actorId, String action,
                        String resourceType, String resourceId,
                        Map<String, Object> detail) {
        String json = toJsonWithSensitiveGuard(tenantId, action, detail);
        String safeActor = bounded(actorId, "actorId");
        String safeAction = bounded(action, "action");
        String safeResourceType = bounded(resourceType, "resourceType");

        inTenantTx(tenantId, conn -> {
            String sql = """
                    INSERT INTO audit_record
                        (tenant_id, actor_id, action, resource_type, resource_id, detail)
                    VALUES (?::uuid, ?, ?, ?, ?, ?::jsonb)
                    RETURNING audit_id::text
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, safeActor);
                ps.setString(3, safeAction);
                ps.setString(4, safeResourceType);
                ps.setString(5, resourceId);
                ps.setString(6, json);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "审计写入未返回 ID（tenant=" + tenantId + ", action=" + action + "）");
                    }
                }
            }
            return null;
        });
    }

    /**
     * 序列化 detail 并做<b>敏感信息检测</b>（D-3）。
     *
     * <p>⚠️ 检测覆盖<b>键与值</b>：键名本身含 {@code studentName} 也会暴露信息形态。
     * 命中即抛异常——<b>整条审计不写</b>，宁可少一条记录。
     */
    /**
     * 审计明细中<b>按键豁免</b>的字段（值不参与敏感信息扫描）。
     *
     * <p>⚠️ <b>为何需要按键豁免</b>（D7 实测踩到）：UUID 形式的内部标识符
     * （{@code runId}/{@code operationId}）其十六进制串里<b>常常含 8–12 位连续数字</b>，
     * 会被「学号」规则误判，导致<b>关键审计被拒写</b>——
     * 而「宁可少一条审计」的原则在这里是<b>错的</b>：
     * 少掉的恰恰是 R-1 最需要的审批痕迹。
     *
     * <p>豁免是<b>按键而非按值</b>的：值里若混入了真实可识别信息，
     * 键名不会变（攻击者无法通过改键名绕过），而豁免只对白名单键生效。
     *
     * <p>⚠️ <b>边界</b>：豁免仅限<b>平台生成的不透明标识符</b>。
     * {@code studentId} <b>不在</b>豁免名单内——它必须被扫描，
     * 因为它虽是随机 ID，但一旦有人塞进真实学号就属违规。
     */
    private static final Set<String> VALUE_SCAN_EXEMPT_KEYS = Set.of(
            "runId", "operationId", "approvalId", "resourceId", "correlationId");

    private String toJsonWithSensitiveGuard(String tenantId, String action,
                                            Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : detail.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            String rendered = value == null ? "null" : String.valueOf(value);
            // ⚠️ 豁免键仍做「长度有界」检查，只跳过模式扫描
            if (!VALUE_SCAN_EXEMPT_KEYS.contains(key)) {
                String probe = key + "=" + rendered;
                for (IdentifyingPattern pattern : IdentifyingPattern.values()) {
                    if (pattern.detect(probe)) {
                        throw new IllegalStateException(
                                "审计明细检出可识别信息（" + pattern.label()
                                        + "），已拒绝写入（tenant=" + tenantId
                                        + ", action=" + action
                                        + ", key=" + key + "）。"
                                        + "宁可少一条审计，不可留下未成年人可识别信息（PIPL）。");
                    }
                }
            }
            if (rendered.length() > AuditPort.MAX_FIELD_LENGTH) {
                throw new IllegalArgumentException(
                        "审计明细值超长（key=" + key + "，"
                                + rendered.length() + " 字符）：标识符不应是长文本");
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(escapeJson(key)).append("\": ")
                    .append(renderJsonValue(rendered));
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private static String renderJsonValue(String rendered) {
        if ("null".equals(rendered) || "true".equals(rendered) || "false".equals(rendered)) {
            return rendered;
        }
        try {
            Long.parseLong(rendered);
            return rendered;
        } catch (NumberFormatException ignored) {
            // 非数字：按字符串渲染
        }
        return '"' + escapeJson(rendered) + '"';
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String bounded(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "审计字段 " + field + " 不能为空（空主体 = 无法追责 = R-1 失效）");
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException(
                    "审计字段 " + field + " 超长（>" + MAX_FIELD_LENGTH + "）："
                            + value.length() + " 字符");
        }
        return value;
    }

    private <T> T inTenantTx(String tenantId, TxAction<T> action) {
        return TransactionalConnectionHolder.inTransaction(
                dataSource,
                new TenantContext(tenantId, "system", Set.of(), 0L),
                action::run);
    }

    @FunctionalInterface
    private interface TxAction<T> {
        T run(Connection conn) throws SQLException;
    }
}