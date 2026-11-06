package cn.studykid.rag.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li><b>运维角色与主数据源角色互斥</b>——{@code rag_ops} 带 BYPASSRLS（平台
 *       全局语料写通道需要，见 RAG-D11 第二刀），一旦它出现在主数据源配置里、
 *       或当前连接的正是它，整条租户隔离会被静默作废；而 {@link #checkNonPrivilegedRole}
 *       只能检出「连上的角色有 BYPASSRLS」，检不出「配置把两个角色写成了同一个」。
 *       所以既要比配置名，也要比运行期 {@code current_user}。</li>
 * </ol>
 *
 * <p>任何一项失败都会抛出 {@link IllegalStateException} 中止启动，
 * 绝不带着不安全的配置对外服务。
 */
@Component
public class RagStartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagStartupSelfCheck.class);

    private final JdbcTemplate jdbcTemplate;

    /** 运维通道是否接入（仅用于日志与提示；角色互检不受它影响，见 {@link #checkOpsRoleSeparation}）。 */
    private final boolean opsEnabled;

    /** 运维数据源用户名（{@code rag.ops.datasource.username}）；空 = 未配置，不参与互检。 */
    private final String opsUsername;

    /** 主数据源用户名（{@code spring.datasource.username}）。 */
    private final String mainUsername;

    public RagStartupSelfCheck(
            JdbcTemplate jdbcTemplate,
            @Value("${rag.ops.enabled:true}") boolean opsEnabled,
            @Value("${rag.ops.datasource.username:}") String opsUsername,
            @Value("${spring.datasource.username:}") String mainUsername) {
        this.jdbcTemplate = jdbcTemplate;
        this.opsEnabled = opsEnabled;
        this.opsUsername = opsUsername == null ? "" : opsUsername.trim();
        this.mainUsername = mainUsername == null ? "" : mainUsername.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = new ArrayList<>();

        checkPgVector(failures);
        checkNonPrivilegedRole(failures);
        checkTenantContextFunction(failures);
        checkRlsEnabled(failures);
        checkFailClosedEmptyCorpus(failures);
        checkOpsRoleSeparation(failures);
        checkSchemaVersion(failures);

        if (!failures.isEmpty()) {
            log.error("RAG 启动自检未通过（{} 项）：{}", failures.size(), failures);
            throw new IllegalStateException(
                    "RAG 启动自检未通过：" + String.join("; ", failures));
        }
        log.info("RAG 启动自检全部通过：pgvector 就绪 / 连接角色非特权 / RLS 生效 / "
                + "无租户上下文只见全局语料 / 运维角色与主数据源角色互斥 / "
                + "库结构与代码版本一致"
                + "（运维通道 " + (opsEnabled ? "已接入" : "显式关闭") + "）。");
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

    /**
     * 运维角色与主数据源角色互斥检查（RAG-D11 第二刀 §2.2）。
     *
     * <p><b>为什么 {@link #checkNonPrivilegedRole} 不够</b>：它检的是「连上来的这个角色
     * 有没有 BYPASSRLS」。而第二刀引入的 {@code rag_ops} <b>本身就是</b>带 BYPASSRLS 的角色
     * （平台全局语料写通道的设计前提），所以「主数据源误填成 rag_ops」这件事有两种形态：
     * <ul>
     *   <li>配置层面两个键写成同一个名字 —— 靠字符串比对检出；</li>
     *   <li>配置没写、被环境变量覆盖、或大小写/引号差异导致实际连上的正是它 ——
     *       只有运行期 {@code current_user} 能检出。</li>
     * </ul>
     * 两种都要，漏一种的后果都是「租户隔离静默作废」：RLS 策略还在、
     * 只是对被绕过的角色不再起作用，而这类事故在日志里没有任何异常。
     *
     * <p><b>不受 {@code rag.ops.enabled} 影响</b>：只要配了运维角色名就互检。
     * 理由是「今天关着」不是「这个名字无害」——把 rag_ops 填进主数据源、
     * 同时临时关掉运维通道，恰好是最危险的一种组合（通道没了、绕过还在）。
     */
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
        assessOpsRoleSeparation(failures, currentUser);
    }

    /**
     * 互斥判定的纯函数部分（不碰库，因此能被单测钉住）。
     *
     * @param failures   收集失败项
     * @param currentUser 运行期 {@code current_user} 读数（可为 null → 只比配置）
     */
    void assessOpsRoleSeparation(List<String> failures, String currentUser) {
        if (opsUsername.isEmpty()) {
            return;
        }
        String normalizedOps = opsUsername.toLowerCase(Locale.ROOT);
        if (!mainUsername.isEmpty()
                && mainUsername.toLowerCase(Locale.ROOT).equals(normalizedOps)) {
            failures.add("主数据源与运维数据源配置成了同一个角色 " + opsUsername
                    + "（运维角色按设计带 BYPASSRLS，作主连接角色会让租户隔离静默失效）"
                    + " → 主数据源必须是 rag_app（NOSUPERUSER + NOBYPASSRLS），"
                    + " 运维通道只能走 rag.ops.datasource.*");
        }
        if (currentUser != null
                && currentUser.trim().toLowerCase(Locale.ROOT).equals(normalizedOps)) {
            failures.add("当前数据库连接角色正是运维角色 " + opsUsername
                    + "（BYPASSRLS 会绕过全部租户 RLS 策略，且不会留下任何异常日志）"
                    + " → 请用 rag_app 启动主数据源");
        }
    }

    /**
     * 【V1.0.2】校验库结构是否与代码版本一致。
     *
     * <p><b>⚠️⚠️ 这一项是「code 已升级、DDL 未迁移」这类事故的拦截器</b>。
     * 2026-11-02 实测踩过：代码新增了 {@code rag_ingest_job.stage/attempt/created_by}
     * 三列，但生产库未跑迁移，导致 {@code GET /ingest-jobs} 每次调用都 500——
     * <b>而服务照常启动、其他端点照常工作</b>，故障只在某个具体功能上暴露，
     * 排查成本极高（要读代码反推 SELECT 了哪些列）。
     *
     * <p>本检查在<b>启动期</b>就拦下这类不一致，并给出可直接执行的修复指引。
     *
     * <p><b>⚠️ 为什么只查「必需项」而不查全部</b>：启动自检要快且稳定。
     * 这里只覆盖<b>代码里被 SELECT/INSERT 硬依赖</b>的列与表——
     * 新增一个可选列不会让检查失败，但漏掉一个被 SELECT 的列则必然 500。
     *
     * @param failures 失败项收集
     */
    void checkSchemaVersion(List<String> failures) {
        // ① 取实际存在性（唯一碰库的地方）
        java.util.Set<String> existingColumns = new java.util.HashSet<>();
        java.util.Set<String> existingTables = new java.util.HashSet<>();
        try {
            jdbcTemplate.query("SELECT table_name, column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'rag'",
                    rs -> {
                        existingColumns.add(rs.getString(1) + "." + rs.getString(2));
                    });
            jdbcTemplate.query("SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = 'rag'",
                    rs -> {
                        existingTables.add(rs.getString(1));
                    });
        } catch (Exception e) {
            failures.add("读取库结构失败（无法校验版本一致性）：" + e.getMessage());
            return;
        }
        // ② 判定（可单测，不碰库）
        assessSchemaVersion(failures, existingColumns, existingTables);
    }

    /**
     * 判断库结构是否满足代码的<b>必需项</b>（纯函数，可单测）。
     *
     * @param failures        失败项收集
     * @param existingColumns 形如 {@code "rag_source.isbn"} 的集合
     * @param existingTables  表名集合
     */
    static void assessSchemaVersion(List<String> failures,
                                    java.util.Set<String> existingColumns,
                                    java.util.Set<String> existingTables) {
        for (String[] rc : REQUIRED_COLUMNS) {
            if (!existingColumns.contains(rc[0] + "." + rc[1])) {
                failures.add("库结构落后于代码：" + rc[0] + "." + rc[1]
                        + " 缺失（" + rc[2] + " 引入）"
                        + " → 请先执行对应迁移（deploy/sql/rag/05_*、06_*），"
                        + "否则依赖该列的功能会运行期 500");
            }
        }
        for (String[] rt : REQUIRED_TABLES) {
            if (!existingTables.contains(rt[0])) {
                failures.add("库结构落后于代码：表 rag." + rt[0] + " 缺失（" + rt[1] + " 引入）"
                        + " → 请先执行 deploy/sql/rag/06_v1_0_2_s3_alter.sql，"
                        + "否则抽检/别名功能不可用");
            }
        }
    }

    /**
     * 代码硬依赖的列：{表, 列, 引入版本}。
     *
     * <p>⚠️ 只列<b>被 SELECT/INSERT 硬依赖</b>的列。
     */
    private static final String[][] REQUIRED_COLUMNS = {
            // V1.0.2 S1：GET /ingest-jobs 直接 SELECT 这三列
            {"rag_ingest_job", "stage", "V1.0.2-S1"},
            {"rag_ingest_job", "attempt", "V1.0.2-S1"},
            {"rag_ingest_job", "created_by", "V1.0.2-S1"},
            // V1.0.2 S3：抽检状态是 VERIFIED 迁移的前置守卫落点
            {"rag_source", "inspection_status", "V1.0.2-S3"},
    };

    /** 代码硬依赖的表：{表, 引入版本}。 */
    private static final String[][] REQUIRED_TABLES = {
            {"rag_inspection_record", "V1.0.2-S3"},
            {"rag_kp_alias", "V1.0.2-S3"},
    };

    /** 原始实现（保留签名，调用新实现）。 */
    private void checkSchemaVersionLegacy(List<String> failures) {        checkSchemaVersion(failures);
    }
}
