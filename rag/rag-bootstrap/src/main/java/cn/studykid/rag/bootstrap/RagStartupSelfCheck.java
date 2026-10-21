package cn.studykid.rag.bootstrap;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检（RAG-D09 G2 条件②「自检通过」）。
 *
 * <p>在上下文刷新完成后、对外服务前执行<b>失败即终止</b>的安全检查，
 * 防止在不满足安全前提的数据库配置下启动并对外暴露：
 * <ol>
 *   <li><b>pgvector 扩展存在</b>——检索 SQL 使用 {@code <=>} 与 {@code ::vector}，
 *       缺扩展则整条稠密链路不可用；</li>
 *   <li><b>连接角色非特权</b>——当前数据库用户<b>不得</b>是超级用户，
 *       且<b>不得</b>具有 {@code BYPASSRLS} 权限；否则租户隔离 RLS 会被静默绕过，
 *       这是教辅多租户的唯一隔离底线；</li>
 *   <li><b>RLS 上下文函数存在</b>——{@code rag_current_tenant()} 必须可调用，
 *       否则租户上下文无法建立；</li>
 *   <li><b>RLS 已在 rag_chunk 启用</b>——表级行级安全必须开启；</li>
 *   <li><b>语料为空时 fail-closed</b>——未建立租户上下文时直接查询 rag_chunk
 *       必须返回 0 行（RLS 阻断一切），证明「无租户 = 不可见」而非「泄露全库」。</li>
 * </ol>
 *
 * <p>任何一项失败都会抛出 {@link IllegalStateException} 中止启动，
 * 绝不带着不安全的配置对外服务。
 */
@Component
public class RagStartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagStartupSelfCheck.class);

    private final JdbcTemplate jdbcTemplate;

    public RagStartupSelfCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = new ArrayList<>();

        checkPgVector(failures);
        checkNonPrivilegedRole(failures);
        checkTenantContextFunction(failures);
        checkRlsEnabled(failures);
        checkFailClosedEmptyCorpus(failures);

        if (!failures.isEmpty()) {
            log.error("RAG 启动自检未通过（{} 项）：{}", failures.size(), failures);
            throw new IllegalStateException(
                    "RAG 启动自检未通过：" + String.join("; ", failures));
        }
        log.info("RAG 启动自检全部通过：pgvector 就绪 / 连接角色非特权 / RLS 生效 / "
                + "空语料 fail-closed。");
    }

    private void checkPgVector(List<String> failures) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
            if (count == null || count == 0) {
                failures.add("pgvector 扩展未安装（检索链路不可用）");
            }
        } catch (Exception e) {
            failures.add("检查 pgvector 扩展失败：" + e.getMessage());
        }
    }

    private void checkNonPrivilegedRole(List<String> failures) {
        try {
            Boolean superUser = jdbcTemplate.queryForObject(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
            Boolean bypassRls = jdbcTemplate.queryForObject(
                    "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
            if (Boolean.TRUE.equals(superUser)) {
                failures.add("连接角色为超级用户（会绕过 RLS，租户隔离失效）");
            }
            if (Boolean.TRUE.equals(bypassRls)) {
                failures.add("连接角色具有 BYPASSRLS（会绕过 RLS，租户隔离失效）");
            }
        } catch (Exception e) {
            failures.add("检查连接角色权限失败：" + e.getMessage());
        }
    }

    private void checkTenantContextFunction(List<String> failures) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_proc WHERE proname = 'rag_current_tenant'",
                    Integer.class);
            if (count == null || count == 0) {
                failures.add("租户上下文函数 rag_current_tenant() 不存在（迁移 V1 未执行？）");
            }
        } catch (Exception e) {
            failures.add("检查租户上下文函数失败：" + e.getMessage());
        }
    }

    private void checkRlsEnabled(List<String> failures) {
        try {
            Boolean enabled = jdbcTemplate.queryForObject(
                    "SELECT relrowsecurity FROM pg_class "
                            + "WHERE relname = 'rag_chunk' AND relkind = 'r'", Boolean.class);
            if (!Boolean.TRUE.equals(enabled)) {
                failures.add("rag_chunk 未启用行级安全（RLS 关闭，租户隔离失效）");
            }
        } catch (Exception e) {
            failures.add("检查 rag_chunk RLS 状态失败：" + e.getMessage());
        }
    }

    /**
     * 空语料 / 无租户上下文时的 fail-closed 探针。
     *
     * <p>⚠️ 不设置任何租户上下文，直接查询：RLS 应阻断一切，返回 0 行。
     * 若返回 > 0，说明存在「无租户即可见全库」的泄漏
     * （通常是特权角色或 RLS 失效），这正是教辅多租户最危险的事故。
     */
    private void checkFailClosedEmptyCorpus(List<String> failures) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM rag_chunk", Integer.class);
            if (count != null && count > 0) {
                failures.add("未建租户上下文即能读取 rag_chunk（" + count
                        + " 行）——fail-closed 不成立，存在全库泄漏风险");
            }
        } catch (Exception e) {
            failures.add("空语料 fail-closed 探针失败：" + e.getMessage());
        }
    }
}
