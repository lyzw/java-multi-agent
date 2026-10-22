package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.infrastructure.persistence.TransactionalConnectionHolder;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

/**
 * {@link PublishedVersionPort} 的 <b>P1 过渡实现</b>（对象级解析 + 回滚守卫）。
 *
 * <p>⚠️⚠️ <b>这不是 §6.2 规定的最终口径，P5 必须替换本类</b>。
 *
 * <p>§6.2 要求「当前 PUBLISHED release → {@code object_versions} → 解析 id→精确版本」，
 * §6.3 要求「<b>当前版本不以 max(seq) 选择</b>」「只认<b>指针指向的</b> PUBLISHED release」。
 * P0 建立的 {@code cap_config_release} 只有发布头与 {@code object_versions}，
 * <b>没有「当前指针」这一实体</b>（指针的写侧 CAS 属 P5）。
 * 本类因此退化为<b>对象级</b>解析：取该对象「最新已发布版本行」。
 *
 * <p><b>回滚守卫</b>：一旦存在 {@code ROLLED_BACK} 的发布，
 * 对象级解析就<b>不再等价于</b>「当前发布所指版本」（§6.3.6 回滚保留原 release、
 * 新建回滚发布并切指针）—— 此时<b>一律解析失败</b>（返回空），让上层 fail-closed。
 * 宁可「无人能授权」，也不可「解析到已回滚的版本」。
 *
 * <p>⚠️ <b>P5 上线前的强制约束</b>：在 P5 的指针读落地前，
 * CAP 授权链路<b>不得</b>在生产开启 —— 本类的语义与 §6.3 存在已知偏差。
 */
public class JdbcPublishedVersionPort implements PublishedVersionPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcPublishedVersionPort.class);

    private static final String SQL_HAS_ROLLED_BACK =
            "SELECT EXISTS (SELECT 1 FROM cap_config_release WHERE status = 'ROLLED_BACK')";

    private static final String SQL_TOOL_VERSION =
            "SELECT version FROM cap_tool_def WHERE id = ? AND published_at IS NOT NULL"
                    + " ORDER BY version DESC LIMIT 1";
    private static final String SQL_SKILL_VERSION =
            "SELECT version FROM cap_skill_def WHERE id = ? AND published_at IS NOT NULL"
                    + " ORDER BY version DESC LIMIT 1";
    private static final String SQL_MCP_VERSION =
            "SELECT version FROM cap_mcp_server_def WHERE id = ? AND published_at IS NOT NULL"
                    + " ORDER BY version DESC LIMIT 1";

    private final DataSource dataSource;

    public JdbcPublishedVersionPort(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<Integer> currentVersion(@NonNull CapObjectType type,
                                                     @NonNull String objectId) {
        if (hasRolledBackRelease()) {
            // ⚠️ fail-closed：回滚后对象级解析不可信，一律拒绝解析
            log.warn("存在已回滚的发布，发布版本解析一律失败（P1 过渡实现 · P5 须替换为指针读）");
            return Optional.empty();
        }
        String sql = switch (type) {
            case TOOL -> SQL_TOOL_VERSION;
            case SKILL -> SQL_SKILL_VERSION;
            case MCP_SERVER -> SQL_MCP_VERSION;
        };
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, objectId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
                        }
                    }
                });
    }

    private boolean hasRolledBackRelease() {
        return TransactionalConnectionHolder.inTransaction(
                dataSource, platformContext(),
                conn -> {
                    try (PreparedStatement ps = conn.prepareStatement(SQL_HAS_ROLLED_BACK);
                         ResultSet rs = ps.executeQuery()) {
                        return rs.next() && rs.getBoolean(1);
                    }
                });
    }

    /**
     * platform 级读取用的租户上下文（零 UUID 哨兵）。
     */
    private static TenantContext platformContext() {
        return new TenantContext("00000000-0000-0000-0000-000000000000", "system",
                java.util.Set.of(), 0L);
    }
}
