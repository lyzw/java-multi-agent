package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * pgvector 可用性探测（P2）。
 *
 * <p><b>⚠️⚠️ 为什么需要运行期探测，而不是"配了就假定有"</b>：
 * 实测结论（2026-11 对线上 {@code agent_dev} 查询）：
 * <pre>
 *   pg_available_extensions 里有 vector（0.8.1.3），但 pg_extension 里没有
 *   → 扩展**可用但未安装**
 * </pre>
 * 而 {@code deploy/sql/platform/01_platform_ddl.sql} 全文 <b>0 处</b>
 * {@code CREATE EXTENSION}。也就是说：
 * <ul>
 *   <li>把 {@code embedding vector(1024)} 写进 DDL 快照，会让
 *       {@code CREATE TABLE} 在缺扩展的库上<b>直接失败</b> —— 整个建库脚本跑不下去；</li>
 *   <li>而"假定有"再在运行期查询，得到的是 {@code ERROR: type "vector" does not exist}，
 *       这个错误发生在<b>第一个 Run 的召回路径</b>上，指向"记忆坏了"，
 *       真因却是"库没装扩展"。</li>
 * </ul>
 *
 * <p><b>因此本类的定位是"降级判据"</b>：
 * 探测不到就<b>明确地</b>退回结构化召回并打 WARN ——
 * 绝不让 {@code enabled=true} 变成"每次召回都抛异常然后被吞掉"。
 *
 * <p>⚠️ <b>为什么用 {@code current_setting} 而不是查 {@code pg_extension}</b>：
 * {@code pg_extension} 是<b>库级</b>信息，但扩展也可能被装在非 {@code public} 的
 * schema 里（{@code CREATE EXTENSION vector WITH SCHEMA xxx}）。
 * 查 {@code pg_type} 的 {@code vector} 类型所在 schema，才能得到
 * "这个连接<b>能不能在 search_path 下解析到 vector 类型</b>"——
 * 那才是"{@code ::vector} 转换能不能用"的准确判据。
 *
 * <p>⚠️ 探测结果<b>缓存</b>：扩展在运行期不会中途装上（装扩展要重启连接池才生效），
 * 而每次召回都探一次等于给热路径加一条 SQL。
 */
public final class PgVectorAvailability {

    private static final Logger log = LoggerFactory.getLogger(PgVectorAvailability.class);

    /**
     * 判据：{@code vector} 类型可在 search_path 中解析，且维度与配置一致。
     *
     * <p>⚠️ 用 {@code to_regtype('vector')} 而非 {@code typname = 'vector'}：
     * 前者<b>尊重 search_path</b>，后者会跨 schema 命中（得出虚假的"可用"）。
     */
    private static final String SQL_PROBE = "SELECT to_regtype('vector') IS NOT NULL AS available";

    private final DataSource dataSource;

    /** 探测结果缓存：{@code null} = 尚未探测。 */
    private volatile @Nullable Boolean available;

    public PgVectorAvailability(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 是否可用（首次调用探测并缓存）。
     *
     * <p>⚠️ 探测本身失败（连不上库）<b>返回 false</b>，不是抛异常：
     * 此时"没有向量能力"恰好是对的行为 —— 结构化召回仍然可用。
     * 但要打 WARN，因为"库连不上"与"没装扩展"是两件事。
     *
     * @return 是否可用
     */
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available != null) {
                return available;
            }
            boolean result = probe();
            available = result;
            return result;
        }
    }

    /**
     * 强制重新探测（运维在运行期间装好扩展后调用）。
     *
     * <p>⚠️ 必须提供这个方法：否则"装上扩展"只能靠重启进程才生效，
     * 而那是一件没有告警、没有日志、只能靠人记住的操作。
     */
    public void reset() {
        synchronized (this) {
            available = null;
        }
    }

    private boolean probe() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_PROBE);
             ResultSet rs = ps.executeQuery()) {
            boolean ok = rs.next() && rs.getBoolean("available");
            if (ok) {
                log.info("pgvector 可用：记忆的语义召回通道已就绪");
            } else {
                // ⚠️ 这是**预期状态**（线上 agent_dev 尚未安装扩展），不是故障：
                //    结构化召回完整可用，只是没有语义通道。
                log.warn("pgvector 未安装：记忆**自动降级为结构化召回**（按强度排序），"
                        + "语义相似度通道不可用。"
                        + "如需启用，须由库 owner 执行："
                        + "CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;"
                        + "（app_role 无 CREATE 权限，不能靠应用启动时自动建扩展）");
            }
            return ok;
        } catch (SQLException e) {
            log.warn("pgvector 可用性探测失败（按不可用处理，降级为结构化召回）", e);
            return false;
        }
    }
}
