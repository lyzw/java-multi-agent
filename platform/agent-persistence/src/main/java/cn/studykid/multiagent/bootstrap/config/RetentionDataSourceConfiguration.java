package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 保留期清理角色（{@code agent_retention}）的数据源装配（P2-2）。
 *
 * <p>⚠️⚠️ <b>为什么必须有第二条凭据</b>：
 * 审计「不可篡改」此前唯一的判据是一个会话可自行设置的 GUC
 * （{@code agent.retention.actor}）—— 任何持有数据库凭据的会话
 * 都能 {@code set_config} 之后删掉审计行。
 * {@code deploy/sql/platform/04_retention_role.sql} 把判据改成了
 * <b>连接凭据是否具备 {@code agent_retention} 成员身份</b>，
 * 并<b>撤销</b>了应用角色对 {@code audit_record} / {@code erasure_certificate} 的 DELETE。
 *
 * <p>⚠️ <b>这条凭证与应用角色的凭据必须分开，理由与 {@code platform_ops} 同源</b>：
 * <ul>
 *   <li>应用凭据丢失 → 泄露范围应当止于「读到租户数据」；</li>
 *   <li>清理凭据丢失 → 后果是「抹掉谁在什么时候做了什么」。
 *       两条凭据合一，等于让前者的爆炸半径无条件升级为后者。</li>
 * </ul>
 *
 * <p>⚠️ <b>fail-closed 规则（与 {@link PlatformOpsDataSourceConfiguration} 一致）</b>：
 * 声明接入却没给凭据 → <b>拒绝启动</b>。不拒绝的话，清理任务会在运行期
 * 报 {@code permission denied}，而现场离「这个环境没配清理凭据」极远。
 */
@Configuration
@ConditionalOnProperty(name = RetentionDataSourceConfiguration.KEY_ENABLED,
        havingValue = "true")
public class RetentionDataSourceConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(RetentionDataSourceConfiguration.class);

    /** 是否接入保留期清理角色。 */
    public static final String KEY_ENABLED = "agent.retention.datasource.enabled";

    /** 清理角色的 JDBC URL。 */
    public static final String KEY_URL = "agent.retention.datasource.url";

    /** 清理角色的数据库用户名。 */
    public static final String KEY_USERNAME = "agent.retention.datasource.username";

    /** 清理角色的数据库口令。 */
    public static final String KEY_PASSWORD = "agent.retention.datasource.password";

    /** 连接池上限。 */
    public static final String KEY_MAX_POOL_SIZE =
            "agent.retention.datasource.max-pool-size";

    /** Bean 名：注入方必须按名引用，避免与主数据源混淆。 */
    public static final String BEAN_NAME = "retentionDataSource";

    /**
     * 保留期清理专用数据源。
     *
     * @param environment 环境（配置来源）
     * @return 以 {@code agent_retention} 身份连接的数据源
     */
    @Bean(name = BEAN_NAME)
    public DataSource retentionDataSource(@NonNull Environment environment) {
        String url = trimmed(environment.getProperty(KEY_URL));
        String username = trimmed(environment.getProperty(KEY_USERNAME));
        String password = environment.getProperty(KEY_PASSWORD);

        // ⚠️ fail-closed：声明了要用却没给凭据 → 拒绝启动
        if (url == null) {
            throw new IllegalStateException("已启用保留期清理角色（" + KEY_ENABLED + "=true）"
                    + "但未配置 JDBC URL（" + KEY_URL + "）。拒绝启动："
                    + "审计行的删除受 ForbidAuditMutation 触发器与角色双重限制，"
                    + "应用角色对该表已无 DELETE 权限，"
                    + "缺清理凭据时继续启动只会得到运行期的 permission denied。");
        }
        if (username == null) {
            throw new IllegalStateException("已启用保留期清理角色（" + KEY_ENABLED + "=true）"
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
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("agent-retention");
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTimeout(5000);

        log.info("保留期清理数据源已接入: user={}, maxPool={}", username, maxPoolSize);
        return new HikariDataSource(cfg);
    }

    /**
     * 取去空白后的配置值；空白视为未配置。
     *
     * <p>⚠️ <b>空白必须视为未配置</b>：否则 {@code url: " "} 会一路走到 JDBC 层
     * 才在「解析连接串」处报错，离真因（配置项没填）很远。
     */
    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
