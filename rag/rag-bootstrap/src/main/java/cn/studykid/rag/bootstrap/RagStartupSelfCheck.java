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
 *   <li><b>pgvector 扩展存在</b>——检索 SQL 使用 {@code <=>} 与向量 cast
 *       （{@code ?::vector} / {@code ?::halfvec}，见 {@code VectorColumn}），
 *       缺扩展则整条稠密链路不可用；</li>
 *   <li><b>连接角色非特权</b>——当前数据库用户<b>不得</b>是超级用户，
 *       且<b>不得</b>具有 {@code BYPASSRLS} 权限；否则租户隔离 RLS 会被静默绕过，
 *       这是教辅多租户的唯一隔离底线；</li>
 *   <li><b>RLS 上下文函数存在</b>——{@code rag_current_tenant()} 必须可调用，
 *       否则租户上下文无法建立；{@code rag_global_tenant_id()} 同样必须在
 *       （V8 全局作用域的权威定义）；</li>
 *   <li><b>RLS 已在 rag_chunk 启用</b>——表级行级安全必须开启；</li>
 *   <li><b>无租户上下文时 fail-closed</b>——未建立租户上下文直接查 rag_chunk，
 *       可见集合必须<b>只含</b>全局语料（{@code scope = 'PLATFORM'}）；
 *       只要混进一行租户私有语料，就是「无租户即可读某租户私有内容」的泄漏。
 *       （V8 双作用域前的表述是「必须返回 0 行」，见
 *       {@link #checkFailClosedEmptyCorpus} 的重述理由。）</li>
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
                + "无租户上下文只见全局语料。");
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
            String role = jdbcTemplate.queryForObject(
                    "SELECT current_user", String.class);
            Boolean superUser = jdbcTemplate.queryForObject(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
            Boolean bypassRls = jdbcTemplate.queryForObject(
                    "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);
            if (Boolean.TRUE.equals(superUser)) {
                failures.add("连接角色 " + role + " 是超级用户（会绕过 RLS，租户隔离失效）"
                        + " → 运行时必须改用非特权应用角色（NOSUPERUSER + NOBYPASSRLS），"
                        + " 如 rag_app；超管角色仅可用于建角色/跑迁移/灌夹具");
            }
            if (Boolean.TRUE.equals(bypassRls)) {
                failures.add("连接角色 " + role + " 具有 BYPASSRLS（会绕过 RLS，租户隔离失效）"
                        + " → 运行时必须改用非特权应用角色（NOSUPERUSER + NOBYPASSRLS），"
                        + " 如 rag_app；BYPASSRLS 角色仅可用于建角色/跑迁移/灌跨租户夹具");
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
            // V8 起全局作用域由 rag_global_tenant_id() 定义；缺它说明迁移没跟上，
            // 而读策略与 scope CHECK 都引用它——半应用的库会让全局语料静默不可见。
            Integer globalFn = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_proc WHERE proname = 'rag_global_tenant_id'",
                    Integer.class);
            if (globalFn == null || globalFn == 0) {
                failures.add("全局作用域函数 rag_global_tenant_id() 不存在（迁移 V8 未执行？）");
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
     * 无租户上下文时的 fail-closed 探针（V8 双作用域下重述）。
     *
     * <p><b>底线从「可见 0 行」改成「只见全局行」</b>：
     * V8 把语料表读策略放开成 {@code tenant_id IN (本租户, 全局哨兵)}，
     * 所以无租户会话看到的不再是空集，而是平台主动发布的全局语料——
     * 这是全局共享的定义本身，不是泄漏。
     *
     * <p>⚠️ 因此判定条件必须是「<b>可见集合里混进了租户私有行吗</b>」，
     * 而不是「可见集合为空吗」。沿用旧断言的表象是
     * 「一导入全局语料，服务就启动失败」——看似更安全，实则是假警报，
     * 会让人把自检关掉。真正要防的泄漏（无租户能读到某租户的私有语料）
     * 由 {@code scope <> 'PLATFORM'} 这条计数抓住。
     *
     * <p>SQL 与 {@code scripts/rag-corpus-scope-counterproof.sh} 的 P3-2 逐字同构，
     * 两处若漂移，反证脚本会先失败。
     */
    private void checkFailClosedEmptyCorpus(List<String> failures) {
        try {
            Integer leaked = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM rag_chunk WHERE scope <> 'PLATFORM'", Integer.class);
            if (leaked != null && leaked > 0) {
                failures.add("未建租户上下文即能读取租户私有语料（" + leaked
                        + " 行 rag_chunk）——fail-closed 不成立，存在跨租户泄漏风险");
            }
        } catch (Exception e) {
            failures.add("无租户 fail-closed 探针失败：" + e.getMessage());
        }
    }
}
