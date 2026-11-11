package cn.studykid.multiagent.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.core.tenant.SubjectContext;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowBinding;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowHooks;
import cn.studykid.multiagent.infrastructure.security.AuthzShadowReporter;
import cn.studykid.multiagent.infrastructure.security.ScopedDataAccess;
import cn.studykid.multiagent.ports.metrics.AgentMetricPort.TraceIdSource;
import cn.studykid.multiagent.ports.metrics.QuestionIdTag;
import cn.studykid.multiagent.ports.metrics.ScenarioTag;
import cn.studykid.multiagent.ports.metrics.TraceFactPort;

/**
 * Trace 事实源 JDBC 读取（P7）。
 *
 * <p><b>⚠️ 这些表全带 FORCE RLS</b>（策略形如 {@code tenant_id = current_tenant_id()}）。
 * 因此必须先用 {@link TenantSessionBinder#bind} 在<b>事务内</b>绑定租户——
 * 用系统连接直读会<b>静默返回空集</b>：症状是指标「全是空」而不报错，
 * 极易被误判为「线上没有流量」。
 *
 * <p><b>⚠️ 只读</b>：本类不写任何表，写完事实后的指标落库走
 * {@code JdbcAgentMetricRepository}。读在租户域、写在平台域。
 *
 * <p>⚠️ <b>本仓是「RLS 依赖度」最典型的样本</b>（2026-11-09 影子态实测）：
 * {@code readRun} / {@code readUsage} / {@code readSteps} / {@code readInvocationTraceId} /
 * {@code readRootAgent} 五处查询<b>没有显式租户谓词</b>，隔离完全靠 RLS 兜底 ——
 * RLS 一退役它们就会越权。故这五处影子比对的 {@code hasTenantPredicate} 一律传 {@code false}，
 * 由 {@code AuthzShadowHooks} 记入独立的「RLS 依赖」计数（<b>不混入 AUTHZ_MISMATCH</b>）。
 * 只有 {@code resolveTraceId} / {@code readInvocationTraceIdScoped} / {@code readToolOperations}
 * 三处自带显式 {@code tenant_id} 谓词，才传 {@code true}。
 */
@Repository
public class JdbcTraceFactRepository implements TraceFactPort {

    private final DataSource dataSource;

    // ── P1 影子态字段（默认 dormant：全部 null/false，零行为变更）──
    private ScopedDataAccess scoped;
    private AuthzShadowReporter reporter;
    private String sentinelTenantId = "";
    private boolean shadowEnabled;

    public JdbcTraceFactRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<TraceFacts> load(@NonNull String tenantId, @NonNull UUID runId) {
        try (Connection conn = openTenant(tenantId)) {
            Optional<RunFacts> run = readRun(conn, tenantId, runId);
            if (run.isEmpty()) {
                conn.commit();
                return Optional.empty();
            }
            if (!run.get().converged()) {
                // 在途不采集 —— 计入会把「还没跑完」算成「没成功」
                conn.commit();
                return Optional.empty();
            }
            UsageFacts usage = readUsage(conn, tenantId, runId);
            List<ToolGroup> tools = readToolOperations(conn, tenantId, runId);
            Set<String> knownTools = readKnownToolNames(conn);
            StepFacts steps = readSteps(conn, tenantId, runId);
            conn.commit();
            return Optional.of(new TraceFacts(run.get(), usage, tools, knownTools, steps));
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Trace 事实失败: run=" + runId, e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 事实读取
    // ══════════════════════════════════════════════════════════════

    private Connection openTenant(String tenantId) throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        // ⚠️ RLS 铁律：事务内、任何业务 SQL 之前绑定
        TenantSessionBinder.bind(conn,
                new TenantContext(tenantId, "agent-metric-collector", Set.of(), 0L));
        return conn;
    }

    private Optional<RunFacts> readRun(Connection conn, String tenantId, UUID runId) throws SQLException {
        // ⚠️ scenario 只认**显式字符串标签**（input_payload.scenario）：
        //    jsonb_typeof 先挡住 object/array —— 否则 ->> 会把整个 JSON 结构
        //    转成文本，把对话原文带进指标宽表（合规红线 + 看板分组基数爆炸）。
        //    内容白名单由 ScenarioTag 在 Java 侧再校验一次（可单测、可反向验证）。
        // ⚠️ questionId 同理只认**显式字符串**：它只喂给 L2 判分源做精确关联，
        //    绝不写宽表；类型闸门挡住把整段题干/对象塞进「题目标识」的调用方。
        String sql = """
                SELECT tenant_id, state, error_code, trace_id, correlation_id, created_at, completed_at,
                       CASE WHEN jsonb_typeof(input_payload -> 'scenario') = 'string'
                            THEN input_payload ->> 'scenario' END AS scenario,
                       CASE WHEN jsonb_typeof(input_payload -> 'questionId') = 'string'
                            THEN input_payload ->> 'questionId' END AS question_id
                FROM public.run WHERE run_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            // ⚠️ hasTenantPredicate=false：SQL **没有** tenant_id 谓词，隔离全靠 RLS。
            shadowCompare("run", tenantId, false);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                shadowTaint("run", tenantId, rs.getString("tenant_id"));
                String traceId = rs.getString("trace_id");
                TraceIdSource source = TraceIdSource.RUN_TRACE_ID;
                if (traceId == null || traceId.isBlank()) {
                    // 委派链路的 trace_id 是真正的会话标识，优先于 correlation_id
                    String invocationTrace = readInvocationTraceId(conn, tenantId, runId);
                    if (invocationTrace != null) {
                        traceId = invocationTrace;
                        source = TraceIdSource.INVOCATION_TRACE_ID;
                    } else {
                        // ⚠️ 降级必须留痕：用 correlation_id 冒充 trace_id 而不标记，
                        //    下游会误以为两个 ID 体系同源
                        traceId = rs.getString("correlation_id");
                        source = TraceIdSource.CORRELATION_FALLBACK;
                    }
                }
                if (traceId == null || traceId.isBlank()) {
                    // 连 correlation_id 都没有：没有可用的会话标识，宁可不采集也不用假值
                    return Optional.empty();
                }
                RootAgent agent = readRootAgent(conn, tenantId, runId);
                return Optional.of(new RunFacts(
                        runId.toString(),
                        rs.getString("state"),
                        rs.getString("error_code"),
                        traceId,
                        source,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null
                                : rs.getTimestamp("completed_at").toInstant(),
                        agent == null ? null : agent.agentId(),
                        agent == null ? null : agent.version(),
                        ScenarioTag.normalize(rs.getString("scenario")),
                        // ⚠️ 非法 / 未标注一律 null，L2 据此 fail-closed；
                        //    绝不把题面原文截断成「题目标识」去模糊匹配题库。
                        QuestionIdTag.normalize(rs.getString("question_id"))));
            }
        }
    }

    private @Nullable String readInvocationTraceId(Connection conn, String tenantId, UUID runId)
            throws SQLException {
        String sql = """
                SELECT tenant_id, trace_id FROM public.agent_invocation
                WHERE run_id = ?::uuid AND depth = 1 ORDER BY started_at LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            // ⚠️ hasTenantPredicate=false：SQL **没有** tenant_id 谓词，隔离全靠 RLS。
            shadowCompare("agent_invocation", tenantId, false);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                shadowTaint("agent_invocation", tenantId, rs.getString("tenant_id"));
                return rs.getString("trace_id");
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点：
     * <ul>
     *   <li><b>租户谓词显式写出</b>（{@code AND tenant_id = ?::uuid}）——
     *       即便 RLS 上下文已建立也不依赖它（纵深防御）。</li>
     *   <li>解析优先级与采集器一致（run.trace_id → invocation.trace_id → correlation_id），
     *       保证「反馈挂的 trace」与「指标统计的 trace」是同一个。</li>
     *   <li><b>不要求 Run 已收敛</b>：在途 Run 也可反馈（如「放弃」）。</li>
     * </ul>
     */
    @Override
    public @NonNull Optional<String> resolveTraceId(
            @NonNull String tenantId, @NonNull UUID runId) {
        try (Connection conn = openTenant(tenantId)) {
            String sql = """
                    SELECT tenant_id, trace_id, correlation_id FROM public.run
                    WHERE run_id = ?::uuid AND tenant_id = ?::uuid
                    """;
            String traceId = null;
            String correlationId = null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, runId.toString());
                ps.setString(2, tenantId);
                // ⚠️ hasTenantPredicate=true：SQL 自带显式 `AND tenant_id = ?::uuid`。
                shadowCompare("run", tenantId, true);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        shadowTaint("run", tenantId, rs.getString("tenant_id"));
                        traceId = rs.getString("trace_id");
                        correlationId = rs.getString("correlation_id");
                    }
                }
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = readInvocationTraceIdScoped(conn, tenantId, runId);
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = correlationId;
            }
            if (traceId == null || traceId.isBlank()) {
                // 没有可用的会话标识：宁可没有反馈，也不落一条挂不上统计单元的记录
                conn.commit();
                return Optional.empty();
            }
            conn.commit();
            return Optional.of(traceId);
        } catch (SQLException ex) {
            throw new IllegalStateException("解析 Run 的会话标识失败", ex);
        }
    }

    /** 委派链路的 trace_id（租户显式谓词版）。 */
    private @Nullable String readInvocationTraceIdScoped(
            Connection conn, String tenantId, UUID runId) throws SQLException {
        String sql = """
                SELECT tenant_id, trace_id FROM public.agent_invocation
                WHERE run_id = ?::uuid AND tenant_id = ?::uuid AND depth = 1
                ORDER BY started_at LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            ps.setString(2, tenantId);
            // ⚠️ hasTenantPredicate=true：SQL 自带显式 `AND tenant_id = ?::uuid`。
            shadowCompare("agent_invocation", tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                shadowTaint("agent_invocation", tenantId, rs.getString("tenant_id"));
                return rs.getString("trace_id");
            }
        }
    }

    private @Nullable RootAgent readRootAgent(Connection conn, String tenantId, UUID runId)
            throws SQLException {
        String sql = """
                SELECT tenant_id, parent_agent_id, parent_agent_version FROM public.agent_invocation
                WHERE run_id = ?::uuid AND depth = 1 ORDER BY started_at LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            // ⚠️ hasTenantPredicate=false：SQL **没有** tenant_id 谓词，隔离全靠 RLS。
            shadowCompare("agent_invocation", tenantId, false);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                shadowTaint("agent_invocation", tenantId, rs.getString("tenant_id"));
                int version = rs.getInt("parent_agent_version");
                return new RootAgent(rs.getString("parent_agent_id"), rs.wasNull() ? null : version);
            }
        }
    }

    private UsageFacts readUsage(Connection conn, String tenantId, UUID runId) throws SQLException {
        // ⚠️ P0-3b：model_id 现在有**两类行** ——
        //    模型调用行（真实模型标识）与工具调用行（"tool:" 前缀 + 工具名）。
        //    聚合时优先取模型行的标识；没有模型行时退回整体最小值（存量数据）。
        //    直接用 min(model_id) 会把工具名混进「按模型分组」，报表失真。
        String sql = """
                SELECT sum(input_tokens)  AS in_tokens,
                       sum(output_tokens) AS out_tokens,
                       sum(cost_amount)   AS cost,
                       min(currency)      AS currency,
                       COALESCE(
                           min(model_id) FILTER (WHERE model_id NOT LIKE 'tool:%'),
                           min(model_id)
                       )                  AS model_id
                FROM public.usage_record WHERE run_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            // ⚠️ hasTenantPredicate=false：SQL **没有** tenant_id 谓词，隔离全靠 RLS。
            //    聚合读无逐行 ⇒ 只能做比对、无法逐行染色（染色需要行级归属）。
            shadowCompare("usage_record", tenantId, false);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                // ⚠️ wasNull() 只对**最近一次**读取有效 —— 连续两次 getLong 后
                //    只判一次会把第一个的空值语义吞掉。
                long in = rs.getLong("in_tokens");
                boolean inNull = rs.wasNull();
                long out = rs.getLong("out_tokens");
                boolean outNull = rs.wasNull();
                return new UsageFacts(
                        inNull ? null : in,
                        outNull ? null : out,
                        rs.getBigDecimal("cost"),
                        rs.getString("currency"),
                        rs.getString("model_id"));
            }
        }
    }

    private List<ToolGroup> readToolOperations(Connection conn, String tenantId, UUID runId)
            throws SQLException {
        // ⚠️ D13（JOIN 从表谓词）：RLS 会对查询树里**每一张表**注入 tenant_id，
        //    应用层不会。退役 RLS 后，`att.operation_id = op.operation_id` 单键 JOIN
        //    只保证「操作号相同」，不保证「同租户」——operation_id 一旦跨租户重复，
        //    就会把他租户的 attempt 统计进本租户的失败率。
        //    故从表补 `att.tenant_id = op.tenant_id`，主表补显式 `op.tenant_id = ?`。
        String sql = """
                SELECT op.tenant_id,
                       op.tool_name,
                       op.request_digest,
                       count(*)                                                AS call_count,
                       count(*) FILTER (WHERE att.state IN ('FAILED','UNKNOWN')) AS failed_count,
                       count(*) FILTER (WHERE att.error_class = 'INVALID_ARGUMENT') AS invalid_arg,
                       bool_or(att.error_class = 'TIMEOUT')                     AS has_timeout,
                       bool_or(att.error_class IN ('UPSTREAM_ERROR','HTTP_5XX','5XX')) AS has_upstream,
                       sum(EXTRACT(EPOCH FROM (att.completed_at - att.started_at)) * 1000)
                           FILTER (WHERE att.completed_at IS NOT NULL)          AS total_ms,
                       max(EXTRACT(EPOCH FROM (att.completed_at - att.started_at)) * 1000) AS max_ms
                FROM public.tool_operation op
                JOIN public.tool_operation_attempt att
                  ON att.operation_id = op.operation_id
                 AND att.tenant_id = op.tenant_id
                WHERE op.run_id = ?::uuid
                  AND op.tenant_id = ?::uuid
                GROUP BY op.tenant_id, op.tool_name, op.request_digest
                ORDER BY op.tool_name
                """;
        Map<String, ToolGroup> groups = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            ps.setString(2, tenantId);
            // ⚠️ hasTenantPredicate=true：SQL 自带显式 `op.tenant_id = ?::uuid`（含从表等值）。
            shadowCompare("tool_operation", tenantId, true);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shadowTaint("tool_operation", tenantId, rs.getString("tenant_id"));
                    String key = rs.getString("tool_name") + "|" + rs.getString("request_digest");
                    double totalMs = rs.getDouble("total_ms");
                    boolean totalNull = rs.wasNull();
                    double maxMs = rs.getDouble("max_ms");
                    boolean maxNull = rs.wasNull();
                    groups.put(key, new ToolGroup(
                            rs.getString("tool_name"),
                            (int) rs.getLong("call_count"),
                            (int) rs.getLong("failed_count"),
                            (int) rs.getLong("invalid_arg"),
                            rs.getBoolean("has_timeout"),
                            rs.getBoolean("has_upstream"),
                            totalNull ? null : (long) totalMs,
                            maxNull ? null : (long) maxMs));
                }
            }
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * 读取执行步骤聚合（P7-2 真实口径来源）。
     *
     * <p>⚠️ 来自 {@code public.run_step}，按 {@code step_type} 分组计数、THINK 求时长。
     * 该表历史上<b>零生产写入方</b>，故绝大多数 Run 在此查不到任何行——此时返回
     * {@link StepFacts#ABSENT}，采集器据此把步数类指标置 {@code null}
     * （未采集 ≠ 退化近似 ≠ 0）。
     */
    private StepFacts readSteps(Connection conn, String tenantId, UUID runId) throws SQLException {
        String sql = """
                SELECT
                    count(*)                                                       AS step_count,
                    count(*) FILTER (WHERE step_type = 'THINK')                     AS think_steps,
                    count(*) FILTER (WHERE step_type = 'TOOL')                      AS tool_steps,
                    count(*) FILTER (WHERE step_type = 'ASK_USER')                  AS ask_steps,
                    coalesce(sum(EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000)
                        FILTER (WHERE step_type = 'THINK'
                                AND completed_at IS NOT NULL), 0)                   AS think_ms,
                    coalesce(max(step_number), 0)                                   AS max_step
                FROM public.run_step WHERE run_id = ?::uuid
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            // ⚠️ hasTenantPredicate=false：SQL **没有** tenant_id 谓词，隔离全靠 RLS。
            //    聚合读无逐行 ⇒ 只能做比对、无法逐行染色。
            shadowCompare("run_step", tenantId, false);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long stepCount = rs.getLong("step_count");
                double thinkMs = rs.getDouble("think_ms");
                boolean thinkNull = rs.wasNull();
                return new StepFacts(
                        stepCount > 0,
                        thinkNull ? 0L : Math.round(thinkMs),
                        rs.getLong("tool_steps"),
                        rs.getLong("ask_steps"),
                        stepCount,
                        (int) rs.getLong("max_step"));
            }
        }
    }

    /**
     * 读取能力目录里已定义的工具标识，用于识别「模型幻觉出的工具」。
     *
     * <p>⚠️ 这是<b>全局存在性</b>判定，不是「该快照的生效闭包」——
     * 精确闭包要展开 {@code execution_snapshot} 版本树。故本判定只能发现
     * 「从未定义过的工具名」，发现不了「已下线版本里的工具」。
     * 文档里明示，不冒充更严格的判据。
     *
     * <p>⚠️⚠️ <b>列名必须是 {@code id}，绝不是 {@code tool_name}</b>：
     * {@code cap_tool_def} 的逻辑标识列叫 {@code id}（形如 {@code tool-profile-read}），
     * {@code name} 是中文显示名（形如 {@code 档案读取}），
     * <b>该表根本没有 {@code tool_name} 列</b>。
     * <p>写错列名的后果<b>不是「少算几个工具」，而是整条采集链路抛
     * {@link SQLException}</b> → 采集器每次都失败 → 宽表永远空白——
     * 且表象是「消息发了但没人消费」，极易误判为 MQ 接线问题。
     * 这个缺陷曾长期潜伏：采集器从未被真正触发过（G2 缺口），
     * 单测又全用 stub 事实端口，从不执行本 SQL。
     *
     * <p>⚠️ 这里读到的只是<b>目录侧</b>的工具名；<b>代码注册</b>的 native 工具
     * （{@code get_student_profile} / {@code record_weakness}）不在目录里，
     * 由 {@code ToolNameRegistry} 补齐，在采集器里取并集。
     *
     * <p>⚠️ <b>本方法刻意不接租户级影子</b>：{@code cap_tool_def} 是<b>平台级目录</b> ——
     * 没有 {@code tenant_id} 列、没有 RLS 策略 ⇒ 行无租户归属 ⇒ 染色无从谈起。
     * 它已登记在 {@code AuthzShadowPolicyDefaults.PLATFORM_LEVEL_RESOURCE_TYPES}，
     * 若在此处按租户级观测，只会制造恒定假违例。
     */
    private Set<String> readKnownToolNames(Connection conn) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM public.cap_tool_def "
                        + "UNION SELECT mcp_tool_name FROM public.cap_tool_def "
                        + "WHERE mcp_tool_name IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private record RootAgent(@Nullable String agentId, @Nullable Integer version) {
    }

    /** 保留：BigDecimal 空值语义与 0 的区别由调用方按需判定。 */
    static @Nullable BigDecimal nullable(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    // ══════════════════════════════════════════════════════════════
    // P1 影子态：比对 + 染色（默认不启用，不影响现有行为）
    // ══════════════════════════════════════════════════════════════

    /**
     * 影子态装配入口（P1 / 13 文 §5.2）：容器里<b>存在</b> {@code AuthzShadowBinding} 即开启，
     * 不存在即完全关闭（不存在「Bean 在、开关关」的半开状态）。
     *
     * <p>⚠️ 用 setter 而不是可选构造参数：本仓既有构造签名被单测与 {@code @Bean} 装配依赖，
     * 改成可选构造参数会把「影子态」渗进每一个调用点。setter 只在启动期被调用一次。
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

    /**
     * 影子比对：Gate 算出的 ScopeFilter vs 本次查询的绑定租户（只记日志）。
     *
     * @param hasTenantPredicate <b>该查询的 SQL 是否真的带了显式租户谓词</b>。
     *        ⚠️ 必须如实填写：传 {@code false} 会被记入「RLS 依赖」清单（退役前必须补应用层过滤）。
     */
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
}
