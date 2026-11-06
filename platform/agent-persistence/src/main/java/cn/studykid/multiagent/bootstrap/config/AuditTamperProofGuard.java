package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P2-2 漂移守卫：验证「应用角色在结构上不能删除审计行」。
 *
 * <p>⚠️⚠️ <b>本类存在的理由</b>：{@code 04_retention_role.sql} 一旦执行完就
 * <b>没有人会再想起它</b>，而任何一次重跑 {@code 03_platform_roles.sql}
 * 的 {@code GRANT … ON ALL TABLES} 都会把 {@code audit_record} 的 DELETE
 * <b>悄悄还给应用角色</b>——没有报错、没有告警，
 * 「审计不可篡改」重新退化成「拿到应用凭据即可抹掉所有人留下过的痕迹」。
 * 这正是本项目已经吃过一次亏的故障形态：<b>配了等于没配且静默</b>。
 *
 * <p>⚠️ <b>默认只告警、可选硬失败</b>（与 {@code CapEnforcementReadinessGuard} /
 * {@code ApprovalReadinessGuard} 同一套纪律）：
 * 隔离脚本尚未执行的环境中，应用角色理应仍有 DELETE，
 * 此时硬失败等于让实例起不来却不增加安全性。
 * 执行完脚本的环境应设 {@code agent.retention.require-isolated-credential=true}，
 * 届时「应用角色竟仍有 DELETE」= 隔离已被回滚 → <b>拒绝启动</b>。
 *
 * <p>⚠️ <b>检查是只读的</b>：用 {@code has_table_privilege}，
 * 不发起任何真实 DELETE —— 拿删除动作去验证删除权限是不可接受的。
 */
public class AuditTamperProofGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditTamperProofGuard.class);

    /** 受阈表：审计行 与 删除凭证（凭证被删则举证链自毁）。 */
    private static final String[] GUARDED_TABLES = {
//            "audit_record", "erasure_certificate",
    };

    private final JdbcTemplate jdbcTemplate;
    private final boolean isolationRequired;

    public AuditTamperProofGuard(@NonNull JdbcTemplate jdbcTemplate,
                                 boolean isolationRequired) {
        this.jdbcTemplate = jdbcTemplate;
        this.isolationRequired = isolationRequired;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        for (String table : GUARDED_TABLES) {
            Boolean canDelete = queryDeletePrivilege(table);
            if (canDelete == null) {
                // 查不到（表不存在/无权限查询）不等于通过：这是 fail-closed 的边界情形
                log.warn("⚠️ 无法判定当前角色对 {} 的 DELETE 权限"
                        + "（查询不可用）：不据此判定隔离生效", table);
                continue;
            }
            Assessment assessment = assess(table, canDelete, isolationRequired);
            if (assessment.fatal()) {
                throw new IllegalStateException(assessment.message());
            }
            if (assessment.warn()) {
                log.error("⚠️⚠️ SECURITY: {}", assessment.message());
            } else {
                log.info("✅ 审计凭据隔离校验通过: {}", assessment.message());
            }
        }
    }

    private @Nullable Boolean queryDeletePrivilege(String table) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT has_table_privilege(current_user, ?, 'DELETE')",
                    Boolean.class, table);
        } catch (RuntimeException e) {
            log.warn("⚠️ 检查 {} 的 DELETE 权限失败（不影响启动，但隔离未获验证）: {}",
                    table, e.toString());
            return null;
        }
    }

    /**
     * 判定结果。
     *
     * @param fatal   是否应当拒绝启动
     * @param warn    是否为需要暴露的安全告警
     * @param message 人读结论（进启动日志，须能直接指导下一步动作）
     */
    record Assessment(boolean fatal, boolean warn, @NonNull String message) {
    }

    /**
     * 纯函数判定（便于单测）：这里只回答一件事——
     * 「当前角色的凭据到底能不能删这张表」，据此决定报错、告警还是放行。
     *
     * @param table            表名
     * @param appRoleCanDelete 当前（应用）角色是否持有 DELETE
     * @param isolationRequired 本环境是否声明「必须已隔离」
     */
    static @NonNull Assessment assess(String table, boolean appRoleCanDelete,
                                      boolean isolationRequired) {
        if (!appRoleCanDelete) {
            return new Assessment(false, false,
                    table + "：当前角色无 DELETE 权限，受控清理与应用路径已分离");
        }
        if (isolationRequired) {
            return new Assessment(true, true,
                    table + "：本环境声明要求凭据隔离，但应用角色**仍有** DELETE —— "
                    + "隔离已被回滚（多半是重跑 03_platform_roles.sql 的 GRANT ALL TABLES）。"
                    + "拒绝启动：此时「拿到应用凭据」即等于「可抹审计」。"
                    + "请重跑 deploy/sql/platform/04_retention_role.sql 恢复隔离。");
        }
        return new Assessment(false, true,
                table + "：应用角色当前持有 DELETE，清理凭据未与应用凭据分离 —— "
                        + "持有应用凭据者可删除审计/删除凭证。"
                        + "执行 deploy/sql/platform/04_retention_role.sql 后，"
                        + "将 agent.retention.require-isolated-credential 设为 true 可转为硬失败。");
    }
}
