package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider;

/**
 * 平台运维角色（BYPASSRLS）数据源的装配。
 *
 * <p>⚠️ <b>本类落地的是「上线阻塞项」</b>：
 * {@code cap_config_pointer} / {@code cap_config_release} / {@code cap_decision_log}
 * 的写策略只放行「本租户的 TENANT 行」，
 * PLATFORM 行的写入必须由平台运维角色执行 ——
 * <b>否则任一租户都能单方面替换全平台生效配置</b>。
 * 在本类之前，生产环境<b>没有任何</b>能写 PLATFORM 行的数据库凭据接入点，
 * 即平台级发布在生产<b>不可用</b>。
 *
 * <p>⚠️ <b>两条 fail-closed 规则</b>：
 * <ul>
 *   <li><b>声明启用却没给凭据 → 拒绝启动</b>：
 *       {@code enabled=true} 而 url/username 缺失，
 *       继续启动等于「以为平台运维已接入、实际没接入」——
 *       等到某次平台发布时才发现，事故现场离配置错误很远。</li>
 *   <li><b>未声明启用 → 不静默降级</b>：
 *       {@code enabled=false}（默认）时 PLATFORM 作用域发布<b>明确失败</b>
 *       （{@code PlatformOpsUnavailableException}），
 *       <b>绝不</b>回落到应用角色去写、再被 RLS 拒掉 ——
 *       那样的失败现场是「RLS 策略拒写」，离「这个环境没配运维凭据」极远。</li>
 * </ul>
 *
 * <p>⚠️ <b>配置风格与主数据源一致</b>：
 * 主数据源走 Spring Boot 标准绑定（{@code SPRING_DATASOURCE_URL} 等），
 * 本数据源用 {@code agent.platform-ops.datasource.*} + 对应环境变量，
 * 两者<b>刻意分开</b>：运维角色与应用角色的凭据生命周期不同
 * （运维凭据应单独轮换、单独授权、单独审计），混在一个配置段里
 * 会让「轮换应用口令」顺手把运维口令也换了。
 */
@Configuration
public class PlatformOpsDataSourceConfiguration {

    /** 是否接入平台运维角色。 */
    public static final String KEY_ENABLED = "agent.platform-ops.datasource.enabled";

    /** 运维角色的 JDBC URL。 */
    public static final String KEY_URL = "agent.platform-ops.datasource.url";

    /** 运维角色的数据库用户名。 */
    public static final String KEY_USERNAME = "agent.platform-ops.datasource.username";

    /** 运维角色的数据库口令。 */
    public static final String KEY_PASSWORD = "agent.platform-ops.datasource.password";

    /** 连接池上限。 */
    public static final String KEY_MAX_POOL_SIZE = "agent.platform-ops.datasource.max-pool-size";

    /**
     * 平台运维数据源持有者。
     *
     * @param environment 环境（配置来源）
     * @return 提供者；未接入时为「已禁用」状态（PLATFORM 发布将 fail-closed）
     */
    @Bean
    public PlatformOpsDataSourceProvider platformOpsDataSourceProvider(
            @NonNull Environment environment) {
        boolean enabled = environment.getProperty(KEY_ENABLED, Boolean.class, Boolean.FALSE);
        if (!enabled) {
            return PlatformOpsDataSourceProvider.disabled();
        }

        String url = trimmed(environment.getProperty(KEY_URL));
        String username = trimmed(environment.getProperty(KEY_USERNAME));
        String password = environment.getProperty(KEY_PASSWORD);

        // ⚠️ fail-closed：声明了要用却没给凭据 → 拒绝启动。
        //    不拒绝的话，运行期第一次平台发布才失败，现场离配置错误极远；
        //    更糟的是「以为已接入」会让发布计划照常排期。
        if (url == null) {
            throw new IllegalStateException("已启用平台运维角色（" + KEY_ENABLED + "=true）"
                    + "但未配置 JDBC URL（" + KEY_URL + "）。拒绝启动："
                    + "平台级发布头与当前指针的写入受 RLS 限制，"
                    + "必须由平台运维角色（BYPASSRLS）执行；"
                    + "缺凭据时静默启动等于「以为已接入、实际没接入」。");
        }
        if (username == null) {
            throw new IllegalStateException("已启用平台运维角色（" + KEY_ENABLED + "=true）"
                    + "但未配置数据库用户名（" + KEY_USERNAME + "）。拒绝启动（理由同 URL 缺失）。");
        }

        int maxPoolSize = environment.getProperty(KEY_MAX_POOL_SIZE, Integer.class, 2);
        if (maxPoolSize < 1) {
            throw new IllegalStateException(KEY_MAX_POOL_SIZE + " 必须为正数，收到: " + maxPoolSize);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(username);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        // ⚠️ 池很小是刻意的：平台运维通道只服务「平台级发布」这一条低频路径，
        //    不该占用与主业务池相当的连接额度。
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("agent-platform-ops");
        // ⚠️ 不在初始化阶段就失败：凭据错误的失败现场应当是
        //    「平台发布时连不上库」，而不是「应用启动时建池失败」——
        //    后者会让一个尚未启用平台发布的实例完全起不来。
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTimeout(5000);

        DataSource pool = new HikariDataSource(cfg);
        return new PlatformOpsDataSourceProvider(true, pool);
    }

    /**
     * 取去空白后的配置值；空白视为未配置。
     *
     * <p>⚠️ <b>空白必须视为未配置</b>：{@code url: " "} 这种值在
     * YAML 里很容易被误写成，落到 JDBC 层才报错，
     * 报错点（连接串解析）离真因（配置项没填）很远。
     */
    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
