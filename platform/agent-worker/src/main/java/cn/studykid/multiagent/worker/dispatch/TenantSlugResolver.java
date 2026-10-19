package cn.studykid.multiagent.worker.dispatch;

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
 * 租户 slug → UUID 解析（P4-D03）。
 *
 * <p><b>⚠️ 为何不在配置里直接写 UUID</b>：
 * 租户 UUID 是数据库生成的、每个环境都不同的值。写进配置意味着
 * 「换环境要改代码旁的配置」，且**没人能看出它指的是哪个租户**——
 * 配置里出现裸 UUID 时，读配置的人无法判断它是租户、Run 还是用户。
 * 故配置只放<b>人类可读的 slug</b>，启动时解析成 UUID。
 *
 * <p><b>⚠️ 解析失败即拒绝启动</b>（fail-closed）：
 * 静默跳过 = 消息永不发布 = 回到「任务永久积压」状态，
 * 而系统表面完全正常（这正是本片要修的断链形态）。
 *
 * <p><b>为何可以跨租户读 {@code tenant} 表</b>：
 * 该表<b>无 RLS</b>（实测 {@code relrowsecurity = f}）——
 * 租户注册表本身必须在 RLS 之前可读，否则无法建立租户上下文。
 * 注意本类<b>只解析 slug，不做任何业务数据读取</b>。
 */
public final class TenantSlugResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantSlugResolver.class);

    private TenantSlugResolver() {
    }

    /**
     * 解析 slug 为租户 UUID。
     *
     * @throws IllegalStateException slug 缺失或不存在（fail-closed）
     */
    public static @NonNull String resolve(@NonNull DataSource dataSource,
                                           @Nullable String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalStateException(
                    "租户 slug 未配置（fail-closed：静默跳过将导致消息永不发布）"
                            + "，请设置 agent.worker.tenant-slug");
        }
        String sql = "SELECT tenant_id::text FROM tenant WHERE slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString(1);
                    log.info("租户 slug 已解析: slug={} → tenantId={}", slug, id);
                    return id;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "解析租户 slug 失败（fail-closed）: slug=" + slug, e);
        }
        // ⚠️ 静默返回 null 会让 Worker 一直空转——必须让启动失败
        throw new IllegalStateException(
                "租户 slug 不存在（fail-closed，拒绝启动）: slug=" + slug
                        + "。请先执行 scripts/dev-seed.sh 或检查 tenant 表。");
    }
}