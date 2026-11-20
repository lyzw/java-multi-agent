package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.persistence.RunEventQueryPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RunEventQueryPort} 的 JDBC 实现（P2-D05）。
 *
 * <p><b>跨租户防护是双层的</b>：
 * <ol>
 *   <li><b>第一道（应用侧）</b>：SQL 显式携带 {@code tenant_id = ?}。</li>
 *   <li><b>第二道（数据库侧）</b>：{@code run_event} 表启用 RLS 策略，
 *       未绑定 {@code app.tenant_id} 时可见 0 行。</li>
 * </ol>
 * 两层机制相互独立。若某天 RLS 策略被误删，第一道仍能挡住；
 * 若应用侧条件被误删，第二道仍能挡住。**任何单层都可能被误改，双层不会同时失效**。
 *
 * <p>⚠️ <b>为什么不用 Spring 的 {@code @Transactional}</b>：
 * RLS 的 {@code set_config(..., is_local=true)} 采用事务局部语义，
 * 自动提交模式下绑定立即失效。本类显式管理事务边界，
 * 走 {@link TenantConnectionLease} 统一入口。
 */
@Repository
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "false", matchIfMissing = true)
public class JdbcRunEventQueryRepository implements RunEventQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunEventQueryRepository.class);

    /**
     * 载荷解析器。
     *
     * <p>⚠️ <b>单例而非每行 new</b>：{@code ObjectMapper} 构造代价高且线程安全，
     * 事件流是高频读取路径，每行 new 一个会让 GC 压力与读取成本同阶。
     */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    /**
     * ⚠️ <b>{@code tenant_id} 条件不可删除</b>（P2-D05 §5.2 S7 反证测试会失败）。
     *
     * <p>{@code sequence_no} 上的唯一约束为 {@code (tenant_id, run_id, sequence_no)}，
     * 已有索引支撑本查询。
     */
    private static final String SQL_FIND_AFTER = """
            SELECT tenant_id, sequence_no, event_type, payload, created_at
            FROM run_event
            WHERE tenant_id = ?::uuid
              AND run_id = ?::uuid
              AND sequence_no > ?
            ORDER BY sequence_no ASC
            LIMIT ?
            """;

    private static final String SQL_FIND_STATE = """
            SELECT tenant_id, state::text
            FROM run
            WHERE tenant_id = ?::uuid AND run_id = ?::uuid
            """;

    /** 单次拉取上限，防止长 Run 一次性返回海量事件。 */
    private static final int DEFAULT_LIMIT = 200;

    private final DataSource dataSource;

    // ── P1 影子态接入（12 文 §4.5 / 13 文 §5.2）──────────────────────────────
    // 默认全部为 null / false ⇒ 现有行为 100% 不变；仅当显式注入且 shadowEnabled=true 时生效。
    private @Nullable ScopedDataAccess scoped;
    private @Nullable AuthzShadowReporter reporter;
    private boolean shadowEnabled;
    private @NonNull String sentinelTenantId;

    /** Spring 装配入口（保持既有单参构造，确保 @Repository 装配不变）。 */
    @Autowired
    public JdbcRunEventQueryRepository(@NonNull DataSource dataSource) {
        this(dataSource, null, null, false, "");
    }

    /**
     * 影子态接线构造（P1 由 {@code AuthzShadowConfiguration} 在开启染色时调用；测试用）。
     */
    JdbcRunEventQueryRepository(@NonNull DataSource dataSource,
                                @Nullable ScopedDataAccess scoped,
                                @Nullable AuthzShadowReporter reporter,
                                boolean shadowEnabled,
                                @NonNull String sentinelTenantId) {
        this.dataSource = dataSource;
        this.scoped = scoped;
        this.reporter = reporter;
        this.shadowEnabled = shadowEnabled;
        this.sentinelTenantId = sentinelTenantId;
    }

    @Override
    public @NonNull List<RunEvent> findAfter(@NonNull String tenantId,
                                            @NonNull String runId,
                                            long afterSequenceNo,
                                            int limit) {
        // ⚠️ 负数游标视为 0：负数无业务含义，
        //    静默接受会让 ORDER BY / > 语义变得难以推理。
        long cursor = Math.max(0L, afterSequenceNo);
        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;

        // ⚠️ P1 影子比对：本次查询绑定租户 vs Gate 算出的 ScopeFilter（只记日志）。
        shadowCompare("run", tenantId, true);
        List<RunEvent> events = new ArrayList<>();
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_AFTER)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                ps.setLong(3, cursor);
                ps.setInt(4, effectiveLimit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // ⚠️ 主体 = 本次查询的租户；行归属 = 结果行真实 tenant_id。
                        String rowTenant = rs.getString("tenant_id");
                        Timestamp created = rs.getTimestamp("created_at");
                        events.add(new RunEvent(
                                rs.getLong("sequence_no"),
                                rs.getString("event_type"),
                                parsePayload(rs.getString("payload")),
                                created == null ? Instant.EPOCH : created.toInstant()));
                        shadowTaint("run", tenantId, rowTenant);
                    }
                }
            }
            // ⚠️ 只读查询：回滚以释放读事务。
            //    复用外层连接时本方法为空操作（租约内部已处理）。
            lease.rollbackQuietly();
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 事件失败", e);
        }

        if (log.isTraceEnabled()) {
            log.trace("Run 事件查询: runId={}, after={}, 返回={} 条",
                    runId, cursor, events.size());
        }
        return List.copyOf(events);
    }

    @Override
    public @Nullable String findState(@NonNull String tenantId, @NonNull String runId) {
        // ⚠️ P1 影子比对：绑定租户 vs Gate ScopeFilter。
        shadowCompare("run", tenantId, true);
        try (TenantConnectionLease lease = tenantConnection(tenantId)) {
            try (PreparedStatement ps = lease.connection().prepareStatement(SQL_FIND_STATE)) {
                ps.setString(1, tenantId);
                ps.setString(2, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    // ⚠️ 主体 = 查询租户；行归属 = 结果行真实 tenant_id（防同义反复）。
                    String rowTenant = rs.getString("tenant_id");
                    String state = rs.getString("state");
                    shadowTaint("run", tenantId, rowTenant);
                    return state;
                }
            } finally {
                lease.rollbackQuietly();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Run 状态失败", e);
        }
    }

    /**
     * 取得已绑定租户的连接。
     *
     * <p>⚠️ 与 {@code JdbcRunCreationRepository} 同源铁律：
     * 外层有事务则复用，否则自开并绑定租户。
     */
    private TenantConnectionLease tenantConnection(String tenantId) throws SQLException {
        java.sql.Connection outer = TransactionalConnectionHolder.current();
        if (outer != null) {
            return TenantConnectionLease.borrowed(outer);
        }
        return TransactionalConnectionHolder.openTenantConnection(
                dataSource, systemContext(tenantId));
    }

    private static TenantContext systemContext(String tenantId) {
        return new TenantContext(tenantId, "system", java.util.Set.of(), 0L);
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     */
    @Autowired(required = false)
    public void bindAuthzShadow(@Nullable AuthzShadowBinding binding) {
        if (binding == null) {
            return;
        }
        this.scoped = binding.scoped();
        this.reporter = binding.reporter();
        this.shadowEnabled = true;
        this.sentinelTenantId = binding.sentinelTenantId();
    }

    /** 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。 */
    void shadowCompare(@NonNull String resourceType,
                       @NonNull String boundTenantId,
                       boolean hasTenantPredicate) {
        AuthzShadowHooks.compare(scoped, reporter, shadowEnabled, resourceType,
                SubjectContext.forTenant(boundTenantId), boundTenantId, hasTenantPredicate);
    }

    /**
     * 逐行染色：结果行的真实 {@code tenant_id} 与 Gate 算出的 ScopeFilter 比对。
     *
     * <p>⚠️ <b>主体与行归属必须分开</b>（12 文 §4.5 · D6/D13）：
     * 主体是<b>发起本次查询的租户</b>，行归属从<b>结果行</b>读出的 {@code tenant_id}。
     */
    void shadowTaint(@NonNull String resourceType,
                     @Nullable String subjectTenantId,
                     @Nullable String rowTenantId) {
        if (rowTenantId == null) {
            return;
        }
        boolean shared = !sentinelTenantId.isBlank() && sentinelTenantId.equals(rowTenantId);
        AuthzShadowHooks.taint(scoped, reporter, shadowEnabled, resourceType, sentinelTenantId,
                subjectTenantId == null ? null : SubjectContext.forTenant(subjectTenantId),
                rowTenantId, shared);
    }

    /**
     * JSON 载荷解析。
     *
     * <p><b>⚠️⛔ 本方法曾是一段手写的字符串切分器，已被 P4-D12 实测推翻并重写</b>。
     * 原实现「为一个扁平对象拖入 Jackson 不划算」，
     * 但 {@code agent-infrastructure} <b>本来就依赖 Jackson</b>（Spring Boot 基线），
     * 「不引入」的收益是 0，而手写切分器的代价是<b>静默的数据损坏</b>。
     *
     * <p><b>手写版实测暴露的三个真实缺陷</b>（全部是「不报错、只是值变了」）：
     * <ol>
     *   <li><b>逗号截断</b>：{@code body.split(",")} 不识别字符串内的逗号，
     *       值里出现一个 {@code ,} 就被劈成两段 → 前半段变成垃圾键值对。
     *       {@code errorCode="E1,E2"} 会被读成 {@code {errorCode=E1, E2=null}}。</li>
     *   <li><b>转义不完整</b>：{@code unquote} 只处理 {@code \"} 与 {@code \\}，
     *       不处理 {@code \n \r \t} → 含换行的值回读为<b>字面量两字符 {@code \n}</b>。</li>
     *   <li><b>类型不可靠</b>：手写 {@code coerce} 把所有整数当 {@code Long}，
     *       写入端的 {@code Integer} 与读取端的 {@code Long} 不对称，
     *       下游做 {@code ==} 比较时会静默失败。</li>
     * </ol>
     *
     * <p>三者都<b>不会抛异常</b>，测试也不会变红——除非专门为它们写用例。
     * 这正是 P4-D08 R-1 的又一次翻版：<b>静默失效靠断言是抓不到的，
     * 只能靠「针对可疑行为的专项用例」抓</b>。
     *
     * <p>⚠️ <b>解析失败不抛异常</b>：返回空载荷而非让整个查询失败。
     * 理由：SSE 订阅是<b>可观测性</b>通道，一条事件解析失败不应
     * 掐断整个事件流。但必须打 warn —— 静默吞掉会掩盖数据问题。
     */
    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = PAYLOAD_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                log.warn("事件载荷不是 JSON 对象，按空载荷处理: {}", abbreviate(json));
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            // ⚠️ 显式逐字段取：SSE 白名单要求标量，嵌套结构一律拒收（fail-closed），
            //    不能让 ObjectMapper 默认把对象/数组反序列化成 Map/List 混进载荷。
            root.properties().forEach(entry -> {
                JsonNode v = entry.getValue();
                if (v.isObject() || v.isArray()) {
                    log.warn("事件载荷含嵌套结构，丢弃该字段: {}", entry.getKey());
                    return;
                }
                result.put(entry.getKey(), toScalar(v));
            });
            return result;
        } catch (Exception e) {
            log.warn("事件载荷解析失败，按空载荷处理: {}（原因={}）",
                    abbreviate(json), e.getMessage());
            return Map.of();
        }
    }

    /** JSON 标量 → Java 标量。整数统一为 {@code Integer}/{@code Long}（按位宽选）。 */
    private static Object toScalar(JsonNode v) {
        if (v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isIntegralNumber()) {
            long n = v.longValue();
            return (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) ? (Object) (int) n : n;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        return v.asString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}