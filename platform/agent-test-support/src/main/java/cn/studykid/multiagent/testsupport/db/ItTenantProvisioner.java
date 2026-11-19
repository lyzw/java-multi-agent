package cn.studykid.multiagent.testsupport.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * IT 专用租户的幂等供给器。
 *
 * <p><b>它解决的不是「造数据麻烦」，而是「测试前提不成立」</b>：
 * 开发机上常驻运行实例，按 {@code agent.worker.tenant-slugs} 轮询消息队列与扫 recoverable Run。
 * 只要 IT 把数据造在真实租户（{@code t-a} / {@code t-b}）上，就会有<b>第二个消费者/改写者</b>
 * 在测试执行的中途改变被测数据的数量与状态 ——
 * 断言随之变成竞态，失败信息却指向完全无关的地方（「期望 1 实得 0」「期望 3 实得 4」）。
 *
 * <p>实测过的三种面貌（同一个根因）：
 * <ol>
 *   <li>{@code PgQueueIT}：刚入队的消息被真实 worker 抢先 claim + ack，领取断言拿到 0 条；</li>
 *   <li>{@code MessagingSwitchGuardIT}：守卫要在「制造残留」与「读取残留」之间保持残留不动，
 *       真实 worker 在中间把消息领走 → 守卫读到 0 → 反证变成「期望抛异常却没抛」；</li>
 *   <li>{@code JdbcStatisticsRepositoryIT}：「基线 + 增量」断言期间，真实 worker 把遗留的
 *       {@code QUEUED} Run 执行成 {@code FAILED}，终态数凭空多 1。</li>
 * </ol>
 *
 * <p><b>约定（由 {@link #provision} 强制）</b>：IT 租户 slug 必须以 {@code it-} 开头。
 * 真实部署租户不以 {@code it-} 开头，因此「IT 造数的租户」与「worker 轮询的租户」
 * 在结构上<b>不可能重合</b>。这不是命名习惯，是可断言的结构约束。
 *
 * <p><b>为什么必须连 agent / agent_version / execution_snapshot 一起建</b>：
 * {@code run.snapshot_id} 必填且带外键 {@code run_snapshot_fk → execution_snapshot}，
 * 只建 tenant 的话任何造 Run 的查询都会被外键挡下。
 *
 * <p><b>⚠️ Agent 是全局实体（2026-11-19 改）</b>：{@code agent} / {@code agent_version} /
 * {@code execution_snapshot} 已不带 {@code tenant_id}，也不再挂租户 RLS；
 * 租户可见性由 {@code tenant_agent_binding} 的多对多绑定决定。因此：
 * <ul>
 *   <li>{@code agent.name} 全局唯一 ⇒ 所有 IT 租户<b>共用</b>同一个 {@code agent1}，
 *       这正是新模型要表达的「一个 Agent 分配给多个租户」，而不是每个租户各造一份；</li>
 *   <li>「该租户能不能看到 agent1」= 绑定表里有没有一行 ACTIVE 绑定；
 *       故 {@link #snapshotId} 必须经绑定解析，不能再按 {@code execution_snapshot.tenant_id} 取。</li>
 * </ul>
 *
 * <p><b>⚠️ 快照不可删除</b>：{@code execution_snapshot} 上有禁止 UPDATE/DELETE 的不可变触发器，
 * 因此本方法只能<b>幂等创建</b>（同一 slug 反复调用不新增行），
 * 不能「每用例建一个随机租户再用完删除」—— 那种写法实际是<b>永远删不掉</b>，
 * 每轮残留一个租户 + 一个快照，还会污染别的 IT 用
 * {@code SELECT snapshot_id FROM execution_snapshot LIMIT 1} 取快照的写法。
 */
public final class ItTenantProvisioner {

    /** IT 租户 slug 的强制前缀。 */
    public static final String SLUG_PREFIX = "it-";

    private ItTenantProvisioner() {}

    /**
     * 校验 slug 是否满足 IT 租户契约。
     *
     * @throws IllegalArgumentException slug 不以 {@code it-} 开头
     */
    public static void requireItSlug(String slug) {
        if (slug == null || !slug.startsWith(SLUG_PREFIX)) {
            throw new IllegalArgumentException(
                    "IT 必须使用 " + SLUG_PREFIX + " 前缀的专用租户（当前=" + slug + "）："
                            + "复用真实租户会与正在运行的实例共用同一份数据，"
                            + "测试结果将取决于谁先执行，而不是代码是否正确");
        }
    }

    /**
     * 幂等创建（或复用）一个 IT 专用租户，返回其 {@code tenant_id}。
     *
     * <p>⚠️ 必须用<b>库 owner</b> 数据源（{@code IT_PG_ADMIN_USER}）：
     * 应用角色对 {@code tenant} / {@code agent} 无写权，且受 RLS 约束。
     *
     * @param adminDataSource owner 连接池
     * @param slug            IT 租户 slug（须以 {@code it-} 开头）
     * @return tenant_id（UUID 文本形态）
     */
    public static String provision(DataSource adminDataSource, String slug) throws SQLException {
        return provision(adminDataSource, slug, "IT 专用租户（幂等创建，勿删）");
    }

    /**
     * 同上，可自定义租户名（便于在库里一眼看出是哪个 IT 建的）。
     */
    public static String provision(DataSource adminDataSource, String slug, String displayName)
            throws SQLException {
        requireItSlug(slug);

        try (Connection conn = adminDataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tenant (slug, name) VALUES (?, ?) "
                                + "ON CONFLICT (slug) DO NOTHING")) {
                    ps.setString(1, slug);
                    ps.setString(2, displayName);
                    ps.executeUpdate();
                }

                String tenantId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT tenant_id::text FROM tenant WHERE slug = ?")) {
                    ps.setString(1, slug);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("IT 租户创建后仍查不到: " + slug);
                        }
                        tenantId = rs.getString(1);
                    }
                }

                // ── P0（E2E 二次评估 · 2026-11-14）：与生产开通事务同形状的运行前置 ──
                // ⚠️ 此前 IT 里 budget_account 靠 dev-it-db.sh 播种、cap_authz_version
                //    靠各 IT 自己 adminExec 补行 —— 测试与生产的形状不一致，
                //    于是「IT 全绿、新租户跑不起来」长期无人发现。
                //    adminDataSource 是库 owner（BYPASSRLS），FORCE RLS 表可直接写。
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO budget_account "
                                + "(tenant_id, currency, total_limit, reserved_amount, settled_amount) "
                                + "VALUES (?::uuid, 'CNY', 1000.000000, 0, 0) "
                                + "ON CONFLICT (tenant_id, currency) DO NOTHING")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cap_authz_version "
                                + "(tenant_id, principal_id, authz_version, version, updated_at) "
                                + "VALUES (?::uuid, 'worker', 1, 0, now()) "
                                + "ON CONFLICT (tenant_id, principal_id) DO NOTHING")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }

                // Agent 是全局实体：dev-it-db.sh 种子已建好唯一的 agent1 + v1 + 快照，
                // 本夹具只做两件事：全局 agent1 不存在时补建（保险），然后给本 IT 租户
                // 加一条 ACTIVE 绑定（一个 Agent 分配给多个租户，正是新模型语义）。
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO agent (name, description) "
                                + "SELECT 'agent1', 'IT 用 agent（全局）' "
                                + "WHERE NOT EXISTS (SELECT 1 FROM agent WHERE name='agent1')")) {
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tenant_agent_binding (tenant_id, agent_id, status, config) "
                                + "SELECT ?::uuid, a.agent_id, 'ACTIVE', '{}'::jsonb "
                                + "FROM agent a WHERE a.name='agent1' "
                                + "AND NOT EXISTS (SELECT 1 FROM tenant_agent_binding b "
                                + " WHERE b.tenant_id=?::uuid AND b.agent_id=a.agent_id)")) {
                    ps.setString(1, tenantId);
                    ps.setString(2, tenantId);
                    ps.executeUpdate();
                }

                // v1 / 快照全局只有一份，同样用 NOT EXISTS 按 agent_id+version 判重。
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO agent_version (agent_id, version_number, definition) "
                                + "SELECT a.agent_id, 1, '{\"kind\":\"tutor\"}'::jsonb "
                                + "FROM agent a WHERE a.name='agent1' "
                                + "AND NOT EXISTS (SELECT 1 FROM agent_version v "
                                + " WHERE v.agent_id=a.agent_id AND v.version_number=1)")) {
                    ps.executeUpdate();
                }

                // ⚠️⚠️ 这里**不能**只靠 `ON CONFLICT DO NOTHING` 保证幂等：
                //     execution_snapshot 的快照 id 是随机值 ⇒ 冲突永不发生；
                //     而它被不可变触发器保护、删不掉，故必须 NOT EXISTS 显式排除，
                //     否则同一 slug 每轮永久泄漏一份快照。
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO execution_snapshot "
                                + "(version_id, agent_config, prompt_config, "
                                + " tool_contracts, run_config) "
                                + "SELECT v.version_id, '{}', '{}', '{}', "
                                + "'{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,"
                                + "\"maxSteps\":20,\"approvalRequired\":\"OPTIONAL\"}' "
                                + "FROM agent_version v JOIN agent a "
                                + "  ON a.agent_id = v.agent_id AND a.name='agent1' "
                                + "WHERE v.version_number = 1 "
                                + "AND NOT EXISTS ("
                                + "  SELECT 1 FROM execution_snapshot s "
                                + "   WHERE s.version_id = v.version_id)")) {
                    ps.executeUpdate();
                }

                conn.commit();
                conn.setAutoCommit(autoCommit);
                return tenantId;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
    }

    /**
     * 取该租户<b>可用的</b>一个执行快照 id。
     *
     * <p>⚠️ <b>必须经 {@code tenant_agent_binding} 过滤</b>：快照已是全局实体、无 tenant 列，
     * 「该租户能用哪些快照」完全由 ACTIVE 绑定决定。
     * {@code SELECT snapshot_id FROM execution_snapshot LIMIT 1} 会拿到未分配给该租户的 Agent
     * 的快照 —— 运行期 resolver 也会因无绑定拒用，造出来的 Run 与真实链路语义不符。
     */
    public static String snapshotId(DataSource adminDataSource, String tenantId) throws SQLException {
        try (Connection conn = adminDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT es.snapshot_id::text "
                             + "FROM tenant_agent_binding b "
                             + "JOIN agent_version v ON v.agent_id = b.agent_id "
                             + "JOIN execution_snapshot es ON es.version_id = v.version_id "
                             + "WHERE b.tenant_id = ?::uuid AND b.status='ACTIVE' "
                             + "ORDER BY v.version_number DESC LIMIT 1")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "租户 " + tenantId + " 没有绑定的执行快照（provision 未生效？）");
                }
                return rs.getString(1);
            }
        }
    }
}
