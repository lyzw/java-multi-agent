package cn.studykid.multiagent.bootstrap.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.messaging.MessagingMode;

/**
 * 消息通道分配（P4-D17 · 可切换消息通道的解析结果）。
 *
 * <p><b>⚠️ 解析规则（全部 fail-closed）</b>：
 * <ul>
 *   <li>全局模式缺失/非法 → <b>拒绝启动</b>（{@link MessagingMode#parse}）；
 *       回落默认值等于「悄悄用了一个没人审过的通道」，而通道选错的症状是
 *       <b>消息静默不消费</b>（系统表面正常、Run 永远不动）；</li>
 *   <li>租户覆盖里的<b>模式值</b>非法 → <b>拒绝启动</b>
 *       （灰度配置写错而静默失效 = 以为在灰度、实际没灰度）；</li>
 *   <li>租户覆盖里的 <b>slug 不在本实例租户清单</b> → <b>只告警不拒绝</b>：
 *       多实例部署下覆盖表通常是共享配置，别人的租户写在这里是正常的；
 *       但必须<b>在启动日志里点名</b>，否则「以为在灰度」会无声发生。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么覆盖用 slug 而非 UUID</b>：UUID 换环境即失效，
 * 而 slug 在本项目是运维侧唯一稳定的租户标识
 * （{@code agent.worker.tenant-slugs} 同样用 slug）。
 */
public final class MessagingChannels {

    private static final Logger log = LoggerFactory.getLogger(MessagingChannels.class);

    /** 全局默认模式。 */
    private final MessagingMode globalMode;

    /** tenantId → slug（启动日志用，避免运维对着 UUID 发呆）。 */
    private final Map<String, String> slugByTenantId;

    /** tenantId → 该租户实际使用的模式。 */
    private final Map<String, MessagingMode> modeByTenantId;

    /**
     * 租户 ID **有序**清单（按 {@code agent.worker.tenant-slugs} 的配置顺序）。
     *
     * <p>⚠️ 必须保序：发布器清单、深度探测的租户顺序都依赖它。
     * {@code Map.copyOf} 返回的是<b>无序</b>不可变 Map —— 用它存有序数据
     * 会让「配置里第一个租户」在运行时变成另一个，
     * 且**不报错**（顺序错乱是最难查的一类）。
     */
    private final List<String> orderedTenantIds;

    /** 覆盖表中出现、但不在本实例租户清单里的 slug（告警项）。 */
    private final List<String> foreignOverrideSlugs;

    private MessagingChannels(MessagingMode globalMode,
                               Map<String, String> slugByTenantId,
                               Map<String, MessagingMode> modeByTenantId,
                               List<String> orderedTenantIds,
                               List<String> foreignOverrideSlugs) {
        this.globalMode = globalMode;
        this.slugByTenantId = Map.copyOf(slugByTenantId);
        this.modeByTenantId = Map.copyOf(modeByTenantId);
        this.orderedTenantIds = List.copyOf(orderedTenantIds);
        this.foreignOverrideSlugs = List.copyOf(foreignOverrideSlugs);
    }

    /**
     * 解析通道分配。
     *
     * @param dataSource   用于 slug → tenantId 解析
     * @param globalModeRaw 全局模式（{@code agent.messaging.mode}）
     * @param overridesRaw  租户覆盖（{@code agent.messaging.tenant-modes}），
     *                      形如 {@code t-b=database,t-c=rabbitmq}；可为空
     * @param tenantSlugs   本实例服务的租户 slug 清单
     */
    public static @NonNull MessagingChannels resolve(@NonNull DataSource dataSource,
                                                     String globalModeRaw,
                                                     String overridesRaw,
                                                     @NonNull List<String> tenantSlugs) {
        MessagingMode global = MessagingMode.parse(globalModeRaw);
        Map<String, MessagingMode> overrides = parseOverrides(overridesRaw);

        Map<String, String> slugByTenantId = new LinkedHashMap<>();
        Map<String, MessagingMode> modeByTenantId = new LinkedHashMap<>();
        List<String> foreign = new ArrayList<>();

        for (String slug : tenantSlugs) {
            String tenantId = resolveTenantId(dataSource, slug);
            slugByTenantId.put(tenantId, slug);
            MessagingMode mode = overrides.getOrDefault(slug, global);
            modeByTenantId.put(tenantId, mode);
        }

        for (Map.Entry<String, MessagingMode> e : overrides.entrySet()) {
            if (!slugByTenantId.containsValue(e.getKey())) {
                foreign.add(e.getKey() + "=" + e.getValue().key());
            }
        }

        MessagingChannels resolved = new MessagingChannels(
                global, slugByTenantId, modeByTenantId,
                List.copyOf(modeByTenantId.keySet()), foreign);
        resolved.logSummary();
        return resolved;
    }

    /**
     * 解析租户覆盖表。
     *
     * @throws IllegalArgumentException 格式非法或模式值非法
     */
    static Map<String, MessagingMode> parseOverrides(String raw) {
        Map<String, MessagingMode> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0 || eq == item.length() - 1) {
                throw new IllegalArgumentException(
                        "租户通道覆盖格式非法: '" + item + "'（应为 slug=mode，可用值: "
                                + MessagingMode.keys() + "）");
            }
            String slug = item.substring(0, eq).trim();
            String modeRaw = item.substring(eq + 1).trim();
            if (out.containsKey(slug)) {
                throw new IllegalArgumentException(
                        "租户通道覆盖重复: " + slug + "（重复项说明配置有歧义，必须显式）");
            }
            // ⚠️ 模式值非法 → 拒绝启动（灰度静默失效比报错更危险）
            out.put(slug, MessagingMode.parse(modeRaw));
        }
        return out;
    }

    private static String resolveTenantId(DataSource dataSource, String slug) {
        String sql = "SELECT tenant_id::text FROM tenant WHERE slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "解析租户 slug 失败（fail-closed）: slug=" + slug
                            + " → " + describeSqlFailure(e), e);
        }
        throw new IllegalStateException(
                "租户 slug 不存在（fail-closed：静默跳过将导致消息永不发布）: slug=" + slug);
    }

    /**
     * 把底层 SQLException 翻译成<b>可执行</b>的排查提示。
     *
     * <p>⚠️ 与 {@code TenantSlugResolver} 同理：外层只写「解析租户 slug 失败」
     * 会把「连不上库」与「连错库」都伪装成「租户没建」，极易误判。
     */
    private static String describeSqlFailure(SQLException e) {
        String state = e.getSQLState();
        String raw = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
        if ("42P01".equals(state)) {
            return "当前连接的库里没有 tenant 表（" + raw + "）"
                    + " → 检查 SPRING_DATASOURCE_URL 指向的库：平台库应为 agent_dev";
        }
        if (state != null && state.startsWith("08")) {
            return "数据库连接失败（" + raw + "）"
                    + " → 检查 host/端口/安全组白名单（本实例线上库仅 5432 开放）";
        }
        return "SQL 异常 [" + state + "] " + raw;
    }

    private void logSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, MessagingMode> e : modeByTenantId.entrySet()) {
            sb.append(slugByTenantId.get(e.getKey()))
                    .append('=').append(e.getValue().key())
                    .append(' ');
        }
        log.info("消息通道分配已解析: 全局模式={}, 租户明细=[{}]", globalMode.key(), sb.toString().trim());
        if (!foreignOverrideSlugs.isEmpty()) {
            log.warn("⚠️ 通道覆盖表中的以下租户不在本实例清单内（可能属其他实例）: {}",
                    foreignOverrideSlugs);
        }
    }

    /** @return 全局默认模式 */
    public @NonNull MessagingMode globalMode() {
        return globalMode;
    }

    /** @return 本实例服务的全部租户 ID（**按配置顺序**） */
    public @NonNull List<String> tenantIds() {
        return orderedTenantIds;
    }

    /**
     * @param tenantId 租户 ID
     * @return 该租户的 slug（运维可读）
     */
    public @NonNull String slugOf(@NonNull String tenantId) {
        String slug = slugByTenantId.get(tenantId);
        if (slug == null) {
            throw new IllegalArgumentException("未知租户: tenantId=" + tenantId);
        }
        return slug;
    }

    /** @return 走 RabbitMQ 通道的租户 ID */
    public @NonNull List<String> rabbitTenantIds() {
        return tenantIdsOf(MessagingMode.RABBITMQ);
    }

    /** @return 走 PostgreSQL 队列通道的租户 ID */
    public @NonNull List<String> databaseTenantIds() {
        return tenantIdsOf(MessagingMode.DATABASE);
    }

    private List<String> tenantIdsOf(MessagingMode mode) {
        List<String> out = new ArrayList<>();
        // ⚠️ 遍历**有序**清单而非 Map.entrySet（Map 无序）
        for (String tenantId : orderedTenantIds) {
            if (modeByTenantId.get(tenantId) == mode) {
                out.add(tenantId);
            }
        }
        return out;
    }

    /** @return 该租户实际使用的模式 */
    public @NonNull MessagingMode modeOf(@NonNull String tenantId) {
        MessagingMode mode = modeByTenantId.get(tenantId);
        // ⚠️ 未知租户不回落默认：回落 = 把消息发到本实例根本没监听的通道
        //    → 消息滞留且无任何报错。宁可拒绝启动。
        if (mode == null) {
            throw new IllegalArgumentException(
                    "未知租户未分配消息通道（fail-closed）: tenantId=" + tenantId);
        }
        return mode;
    }

    /** @return RabbitMQ 通道是否有活动租户 */
    public boolean rabbitActive() {
        return !rabbitTenantIds().isEmpty();
    }

    /** @return PostgreSQL 通道是否有活动租户 */
    public boolean databaseActive() {
        return !databaseTenantIds().isEmpty();
    }

    /** @return 被租户覆盖改变过模式的租户数（灰度广度） */
    public int overriddenTenantCount() {
        int n = 0;
        for (MessagingMode mode : modeByTenantId.values()) {
            if (mode != globalMode) {
                n++;
            }
        }
        return n;
    }

    /** @return 覆盖表中不属于本实例的 slug（告警用） */
    public @NonNull List<String> foreignOverrideSlugs() {
        return foreignOverrideSlugs;
    }

    /** @return 本实例的 slug 集合（运维可读） */
    public @NonNull Set<String> slugs() {
        return Set.copyOf(slugByTenantId.values());
    }
}
