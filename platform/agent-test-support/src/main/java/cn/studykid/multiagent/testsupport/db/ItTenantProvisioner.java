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
 * {@code run.snapshot_id} 必填且带复合外键 {@code (tenant_id, snapshot_id)}，
 * 只建 tenant 的话任何造 Run 的查询都会被外键挡下。
 *
 * <p><b>⚠️ 快照不可删除</b>：{@code execution_snapshot} 上有禁止 UPDATE/DELETE 的不可变触发器，
 * 因此本方法只能<b>幂等创建</b>（同一 slug 反复调用不新增行），
 * 不能「每用例建一个随机租户再用完删除」—— 那种写法实际是<b>永远删不掉</b>，
 * 每轮残留一个租户 + 一个快照，还会污染别的 IT 用
 * {@code SELECT snapshot_id FROM execution_snapshot LIMIT 1} 取快照的写法
 * （取到别的租户的快照 → 复合外键报错，现象与本 IT 无关）。
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

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO agent (tenant_id, name) VALUES (?::uuid, 'agent1') "
                                + "ON CONFLICT (tenant_id, name) DO NOTHING")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO agent_version (tenant_id, agent_id, version_number, definition) "
                                + "SELECT a.tenant_id, a.agent_id, 1, '{\"kind\":\"tutor\"}'::jsonb "
                                + "FROM agent a WHERE a.tenant_id = ?::uuid AND a.name = 'agent1' "
                                + "ON CONFLICT (tenant_id, agent_id, version_number) DO NOTHING")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }

                // ⚠️⚠️ 这里**不能**只靠 `ON CONFLICT DO NOTHING` 保证幂等：
                //     execution_snapshot 的唯一约束是 (tenant_id, snapshot_id)，
                //     而 snapshot_id 每次插入都是新的随机值 ⇒ **冲突永远不会发生**，
                //     `ON CONFLICT DO NOTHING` 等于没写。实测同一 slug 连跑 5 轮后
                //     库里留下 5 份快照 —— 而快照被不可变触发器保护、**删不掉**，
                //     于是「幂等创建」变成了每轮永久泄漏一份。
                //     故必须用 NOT EXISTS 把「已有同版本快照」显式排除。
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO execution_snapshot "
                                + "(tenant_id, version_id, agent_config, prompt_config, "
                                + " tool_contracts, run_config) "
                                + "SELECT v.tenant_id, v.version_id, '{}', '{}', '{}', "
                                + "'{\"maxCostPerRun\":\"2.00\",\"maxLlmCalls\":12,"
                                + "\"maxSteps\":20,\"approvalRequired\":\"OPTIONAL\"}' "
                                + "FROM agent_version v JOIN agent a "
                                + "  ON a.agent_id = v.agent_id AND a.tenant_id = v.tenant_id "
                                + "WHERE v.tenant_id = ?::uuid AND a.name = 'agent1' "
                                + "AND v.version_number = 1 "
                                + "AND NOT EXISTS ("
                                + "  SELECT 1 FROM execution_snapshot s "
                                + "   WHERE s.tenant_id = v.tenant_id AND s.version_id = v.version_id)")) {
                    ps.setString(1, tenantId);
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
     * 取该租户自己的一个执行快照 id。
     *
     * <p>⚠️ <b>必须按租户过滤</b>：{@code SELECT snapshot_id FROM execution_snapshot LIMIT 1}
     * 拿到的可能是别的租户的快照，插 Run 时复合外键 {@code (tenant_id, snapshot_id)} 会失败，
     * 而报错信息只说外键冲突，不说「你取错租户的快照了」。
     */
    public static String snapshotId(DataSource adminDataSource, String tenantId) throws SQLException {
        try (Connection conn = adminDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT snapshot_id::text FROM execution_snapshot "
                             + "WHERE tenant_id = ?::uuid LIMIT 1")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "租户 " + tenantId + " 没有执行快照（provision 未生效？）");
                }
                return rs.getString(1);
            }
        }
    }
}
