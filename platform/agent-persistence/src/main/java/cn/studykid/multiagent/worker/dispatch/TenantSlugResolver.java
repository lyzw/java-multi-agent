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
                    "解析租户 slug 失败（fail-closed）: slug=" + slug
                            + " → " + describe(e), e);
        }
        // ⚠️ 静默返回 null 会让 Worker 一直空转——必须让启动失败
        throw new IllegalStateException(
                "租户 slug 不存在（fail-closed，拒绝启动）: slug=" + slug
                        + "。请先执行 scripts/dev-seed.sh 或检查 tenant 表。");
    }

    /**
     * 把底层 SQLException 翻译成<b>可执行</b>的排查提示。
     *
     * <p>⚠️ 为什么必须翻译：外层若只写「解析租户 slug 失败」，运维看到的只是
     * 「slug 解析不出来」，而真实原因可能是<b>连不上库</b>（端口/白名单）
     * 或<b>连错库</b>（datasource 指向了没有 tenant 表的库，例如 RAG 的 study_rag）。
     * 这两类都会以同一句 fail-closed 呈现，极易误判为「租户没建」。
     */
    private static String describe(@NonNull SQLException e) {
        String state = e.getSQLState();
        String raw = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
        if ("42P01".equals(state)) {
            return "当前连接的库里没有 tenant 表（" + raw + "）"
                    + " → 检查 SPRING_DATASOURCE_URL 指向的库："
                    + "平台库应为 agent_dev；若指向 RAG 的 study_rag 必然缺 tenant 表";
        }
        if (state != null && state.startsWith("08")) {
            return "数据库连接失败（" + raw + "）"
                    + " → 检查 host/端口/安全组白名单："
                    + "本实例线上库仅 5432 开放，5433 等端口均不通";
        }
        return "SQL 异常 [" + state + "] " + raw;
    }
}