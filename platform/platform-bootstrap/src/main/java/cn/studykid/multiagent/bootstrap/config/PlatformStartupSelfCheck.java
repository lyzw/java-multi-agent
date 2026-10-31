package cn.studykid.multiagent.bootstrap.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 平台启动自检（P0-2 修复：杜绝主数据源以 BYPASSRLS 角色静默绕过 RLS）。
 *
 * <p>在上下文刷新完成后、对外服务前执行<b>失败即终止</b>的安全检查，
 * 防止在不满足安全前提的数据库配置下启动并对外暴露：
 * <ol>
 *   <li><b>连接角色非特权</b>——当前数据库用户<b>不得</b>是超级用户，
 *       且<b>不得</b>具有 {@code BYPASSRLS} 权限；否则租户隔离 RLS 会被静默绕过，
 *       这是教辅多租户的唯一隔离底线。RAG 侧已有 {@code RagStartupSelfCheck}，
 *       平台侧此前<b>缺失</b>该自检，dev 主数据源用 {@code ai_agent}（BYPASSRLS）
 *       时主链路 RLS 完全失效却无任何报错。</li>
 *   <li><b>运维角色与主数据源角色互斥</b>——{@code platform_ops} 按设计带 BYPASSRLS
 *       （平台级发布写入通道需要），一旦它出现在主数据源连接上，整条租户隔离
 *       会被静默作废；故必须比配置名，也必须比运行期 {@code current_user}。</li>
 * </ol>
 *
 * <p>任何一项失败都会抛出 {@link IllegalStateException} 中止启动，
 * 绝不带着不安全的配置对外服务。纯判定逻辑（{@link #assessNonPrivilegedRole} /
 * {@link #assessOpsRoleSeparation}）与库交互分离，便于单测钉死。
 */
public class PlatformStartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformStartupSelfCheck.class);

    private final JdbcTemplate jdbcTemplate;
    /** 运维数据源用户名（{@code agent.platform-ops.datasource.username}）；空 = 未配置，不参与互检。 */
    private final String opsUsername;

    public PlatformStartupSelfCheck(
            JdbcTemplate jdbcTemplate,
            String opsUsername) {
        this.jdbcTemplate = jdbcTemplate;
        this.opsUsername = opsUsername == null ? "" : opsUsername.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = new ArrayList<>();

        checkNonPrivilegedRole(failures);
        checkOpsRoleSeparation(failures);

        if (!failures.isEmpty()) {
            log.error("平台启动自检未通过（{} 项）：{}", failures.size(), failures);
            throw new IllegalStateException(
                    "平台启动自检未通过（租户隔离 RLS 可能被静默绕过）：" + String.join("; ", failures));
        }
        log.info("平台启动自检全部通过：主数据源连接角色非特权（无 BYPASSRLS/超级用户），"
                + "租户隔离 RLS 未被静默绕过；运维角色与主数据源角色互斥。");
    }

    private void checkNonPrivilegedRole(List<String> failures) {
        try {
            String role = jdbcTemplate.queryForObject("SELECT current_user", String.class);
            Boolean superUser = jdbcTemplate.queryForObject(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
            Boolean bypassRls = jdbcTemplate.queryForObject(
                    "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
            assessNonPrivilegedRole(failures, role, superUser, bypassRls);
        } catch (Exception e) {
            failures.add("检查连接角色权限失败：" + e.getMessage());
        }
    }

    private void checkOpsRoleSeparation(List<String> failures) {
        if (opsUsername.isEmpty()) {
            return;
        }
        String currentUser;
        try {
            currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        } catch (Exception e) {
            failures.add("检查运维角色互斥失败：" + e.getMessage());
            return;
        }
        assessOpsRoleSeparation(failures, currentUser, opsUsername);
    }

    /**
     * 非特权角色判定（纯函数，不碰库，可被单测钉死）。
     *
     * @param failures   收集失败项
     * @param role       运行期 {@code current_user}
     * @param superUser  是否超级用户
     * @param bypassRls  是否具有 BYPASSRLS
     */
    static void assessNonPrivilegedRole(List<String> failures, String role,
                                        Boolean superUser, Boolean bypassRls) {
        if (Boolean.TRUE.equals(superUser)) {
            failures.add("连接角色 " + role + " 是超级用户（会绕过 RLS，租户隔离失效）"
                    + " → 运行时必须改用非特权应用角色（NOSUPERUSER + NOBYPASSRLS），如 app_agent；"
                    + " 超管角色仅可用于建角色/跑迁移/灌夹具");
        }
        if (Boolean.TRUE.equals(bypassRls)) {
            failures.add("连接角色 " + role + " 具有 BYPASSRLS（会绕过 RLS，租户隔离失效）"
                    + " → 运行时必须改用非特权应用角色（NOSUPERUSER + NOBYPASSRLS），如 app_agent；"
                    + " BYPASSRLS 角色仅可用于建角色/跑迁移/灌跨租户夹具");
        }
    }

    /**
     * 运维角色互斥判定（纯函数，不碰库，可被单测钉死）。
     *
     * <p>运维角色按设计带 BYPASSRLS，若它出现在主数据源连接上，
     * 全部租户 RLS 策略会被静默绕过且无任何异常日志。
     *
     * @param failures     收集失败项
     * @param currentUser  运行期 {@code current_user}
     * @param opsUsername  运维数据源用户名（空 = 不参与互检）
     */
    static void assessOpsRoleSeparation(List<String> failures, String currentUser, String opsUsername) {
        if (opsUsername == null || opsUsername.isEmpty()) {
            return;
        }
        String normalizedOps = opsUsername.toLowerCase(Locale.ROOT);
        if (currentUser != null
                && currentUser.trim().toLowerCase(Locale.ROOT).equals(normalizedOps)) {
            failures.add("当前数据库连接角色正是运维角色 " + opsUsername
                    + "（BYPASSRLS 会绕过全部租户 RLS 策略，且不会留下任何异常日志）"
                    + " → 请用 app_agent 启动主数据源，运维通道只走 agent.platform-ops.datasource.*");
        }
    }
}
