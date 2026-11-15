package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 租户运行前置的「写侧显式建行」守卫（P0 · E2E 二次评估 2026-11-14）。
 *
 * <p><b>为什么需要它</b>：E2E 实测「新租户创建 Run 返回 201，随后静默 FAILED」，
 * 根因是租户开通事务只写了「三处齐」，漏了 {@code budget_account} 与
 * {@code cap_authz_version(tenant_id,'worker')}。当时唯一能补数的是人工 SQL，
 * 生产没有任何写入方。修复落在一个单一真相源
 * {@link TenantRuntimeDefaults}，由两条开通链 + IT 夹具调用。
 *
 * <p><b>可证伪命题</b>：
 * <ol>
 *   <li>两条生产开通链与 IT 夹具都必须调用 {@code insertRuntimePrerequisites}；</li>
 *   <li>走 {@code app_agent}（NOBYPASSRLS）的那条链必须在业务 SQL 前绑定租户上下文
 *       ——FORCE RLS 表未绑定时 INSERT 静默 0 行（假绿）；</li>
 *   <li>六处生产读点必须继续用 {@code require()} fail-closed，
 *       <b>不得</b>改成 {@code currentOrInitialize()}（缺行补建 = 把「从未授权」
 *       伪装成「版本 1 的已授权主体」）；</li>
 *   <li>账号开通脚本必须补主体授权版本行（否则主体创建后 CapDualWrite 挂起）。</li>
 * </ol>
 *
 * <p><b>诚实边界</b>：这是<b>文本信号</b>扫描，不是数据流分析。把调用抽成常量、
 * 经反射间接调用等写法抓不到；它拦得住的是「照现有写法删掉修复点」或
 * 「图省事把读点降级成读时补建」这两类最可能的回归。
 *
 * <p><b>反证</b>：{@link ScannerSelfProof} 用合成片段证明守卫不是恒绿——
 * 撤掉任一修复点，扫描结果必须变红。
 */
class TenantRuntimeDefaultsArchitectureTest {

    /** 单一真相源的调用信号。 */
    private static final Pattern CALL_PREREQUISITES = Pattern.compile(
            "TenantRuntimeDefaults\\s*\\.\\s*insertRuntimePrerequisites\\s*\\(");

    /** 事务内绑定租户上下文（FORCE RLS 表写入前置）。 */
    private static final Pattern BIND_TENANT = Pattern.compile(
            "TenantSessionBinder\\s*\\.\\s*bind\\s*\\(");

    /** 严格读：允许换行后接 `.require(` 的写法。 */
    private static final Pattern STRICT_READ = Pattern.compile(
            "authzVersions[\\s.]*require\\s*\\(");

    /** 读时补建：生产读点一旦出现即为红线。 */
    private static final Pattern READ_SIDE_INIT = Pattern.compile(
            "currentOrInitialize\\s*\\(");

    private static final Path ROOT = platformRoot();

    private static String persistence(String simpleName) {
        return "agent-persistence/src/main/java/cn/studykid/multiagent/"
                + "infrastructure/persistence/" + simpleName;
    }

    /** 两条生产开通链：必须调用单一真相源。 */
    private static final List<String> PRODUCTION_CHAINS = List.of(
            persistence("JdbcPlatformTenantRepository.java"),
            persistence("AdminTenantRepository.java"));

    /**
     * IT 夹具单独处理：{@code agent-persistence} 以 test 作用域依赖
     * {@code agent-test-support}，若后者 main 反向依赖前者 main 就是 reactor 循环。
     * 故夹具只能保持「同形状 SQL 副本」，由本守卫锁住形状一致性。
     */
    private static final String IT_FIXTURE =
            "agent-test-support/src/main/java/cn/studykid/multiagent/"
                    + "testsupport/db/ItTenantProvisioner.java";

    /** 六处生产读点（严格 require，不许读时补建）。 */
    private static final List<String> READ_SITES = List.of(
            "agent-application/src/main/java/cn/studykid/multiagent/application/"
                    + "cap/CapExecutionFlow.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/"
                    + "cap/InvocationValidator.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/"
                    + "cap/CapDualWriteService.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/"
                    + "cap/ExecutionAdmissionChecker.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/"
                    + "orchestration/DelegationGuard.java",
            "agent-application/src/main/java/cn/studykid/multiagent/application/"
                    + "orchestration/DelegationExecutor.java");

    @Nested
    @DisplayName("写侧：开通即建行")
    class WriteSide {

        @Test
        @DisplayName("⚠️⚠️ 两条生产开通链必须调用 insertRuntimePrerequisites")
        void everyProductionChainCallsSingleSourceOfTruth() {
            List<String> missing = new ArrayList<>();
            for (String rel : PRODUCTION_CHAINS) {
                Path file = ROOT.resolve(rel);
                assertThat(Files.isRegularFile(file))
                        .as("守卫不得空转：找不到源码 %s（platform 根目录定位可能失效）", rel)
                        .isTrue();
                if (!CALL_PREREQUISITES.matcher(readCode(file)).find()) {
                    missing.add(rel);
                }
            }
            assertThat(missing)
                    .as("⚠️⚠️ 这些开通链没有补齐租户运行前置：新租户会「创建 Run 返回 201 → 静默 FAILED」"
                            + "（BUDGET_EXHAUSTED / AUTHZ_UNAVAILABLE）。"
                            + "不要在读点补建，要在开通事务里显式建行。")
                    .isEmpty();
        }

        @Test
        @DisplayName("⚠️⚠️ IT 夹具必须与生产同形状（两条 INSERT + 幂等 + 同一 worker 主体）")
        void itFixtureKeepsSameShape() {
            Path fixture = ROOT.resolve(IT_FIXTURE);
            assertThat(Files.isRegularFile(fixture)).isTrue();
            String code = readCode(fixture);
            assertThat(code)
                    .as("IT 夹具缺 budget_account ⇒ IT 全绿而新租户跑不起来（形状不一致）")
                    .contains("INSERT INTO budget_account");
            assertThat(code)
                    .as("IT 夹具缺 cap_authz_version worker 行 ⇒ 同上")
                    .contains("INSERT INTO cap_authz_version");
            assertThat(code)
                    .as("worker 主体字面量必须与生产常量 WORKER_PRINCIPAL_ID 一致")
                    .contains("'worker'");
            assertThat(code)
                    .as("预算额度必须与 TenantRuntimeDefaults.DEFAULT_TENANT_BUDGET_LIMIT 同口径")
                    .contains("1000.000000");
            assertThat(code.split("ON CONFLICT", -1).length - 1)
                    .as("两条 INSERT 都必须幂等")
                    .isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("⚠️⚠️ app_agent（NOBYPASSRLS）开通链必须在写 FORCE RLS 表前绑定租户")
        void appAgentChainBindsTenantBeforeWrite() {
            Path file = ROOT.resolve(persistence("AdminTenantRepository.java"));
            String code = readCode(file);
            int bind = indexOf(code, BIND_TENANT);
            int call = indexOf(code, CALL_PREREQUISITES);
            assertThat(bind)
                    .as("AdminTenantRepository 走 app_agent（NOBYPASSRLS），"
                            + "budget_account / cap_authz_version 均是 FORCE RLS 表；"
                            + "不绑定 GUC 就 INSERT 会静默 0 行（假绿）")
                    .isGreaterThanOrEqualTo(0);
            assertThat(call).isGreaterThanOrEqualTo(0);
            assertThat(bind)
                    .as("绑定必须在 insertRuntimePrerequisites 之前、同一事务内")
                    .isLessThan(call);
        }

        @Test
        @DisplayName("✅ 单一真相源必须同时写预算账户与 worker 授权版本行")
        void singleSourceWritesBothRows() {
            String code = readCode(ROOT.resolve(persistence("TenantRuntimeDefaults.java")));
            assertThat(code)
                    .as("缺 budget_account ⇒ 模型预留 NO_ACCOUNT ⇒ BUDGET_EXHAUSTED")
                    .contains("INSERT INTO budget_account");
            assertThat(code)
                    .as("缺 cap_authz_version(worker) ⇒ 执行链 require() fail-closed ⇒ AUTHZ_UNAVAILABLE")
                    .contains("INSERT INTO cap_authz_version");
            assertThat(code)
                    .as("两条 INSERT 必须幂等；重复开通不得降版或覆盖既有预算")
                    .contains("ON CONFLICT");
        }

        @Test
        @DisplayName("✅ 账号开通脚本必须补主体授权版本行")
        void accountSeedWritesPrincipalAuthzVersion() {
            Path file = ROOT.getParent().resolve("scripts/dev-seed.sh");
            assertThat(Files.isRegularFile(file)).isTrue();
            assertThat(readRaw(file))
                    .as("主体创建后若没有 (tenant_id, principal_id::text) 授权版本行，"
                            + "CapDualWriteService 的 require() 会挂起")
                    .contains("INSERT INTO cap_authz_version");
        }
    }

    @Nested
    @DisplayName("读侧：绝不降级为读时补建")
    class ReadSide {

        @Test
        @DisplayName("⚠️⚠️ 六处生产读点必须继续 require() fail-closed")
        void allReadSitesUseStrictRequire() {
            List<String> violations = new ArrayList<>();
            for (String rel : READ_SITES) {
                Path file = ROOT.resolve(rel);
                assertThat(Files.isRegularFile(file))
                        .as("守卫不得空转：找不到源码 %s", rel)
                        .isTrue();
                String code = readCode(file);
                if (!STRICT_READ.matcher(code).find()) {
                    violations.add(rel + " 缺 require()");
                }
                if (READ_SIDE_INIT.matcher(code).find()) {
                    violations.add(rel + " 出现读时补建 currentOrInitialize()");
                }
            }
            assertThat(violations)
                    .as("⚠️⚠️ 缺行 = 该主体从未被授权，必须 fail-closed；"
                            + "currentOrInitialize 会补建版本 1 并继续，"
                            + "把「从未授权」伪装成「版本 1 的已授权主体」。"
                            + "补齐数据的位置是开通事务，不是读点。")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("扫描器自证（反证）")
    class ScannerSelfProof {

        @Test
        @DisplayName("⚠️⚠️ 撤掉调用信号必须被检出（守卫不能恒绿）")
        void detectsRemovedCall() {
            String withoutCall = """
                    class Broken {
                        void provision(Connection conn, String tenantId) {
                            // 运行前置被删了
                            conn.commit();
                        }
                    }
                    """;
            assertThat(CALL_PREREQUISITES.matcher(withoutCall).find())
                    .as("删掉 insertRuntimePrerequisites 后不得命中")
                    .isFalse();

            String withCall = """
                    class Fixed {
                        void provision(Connection conn, String tenantId) {
                            TenantRuntimeDefaults.insertRuntimePrerequisites(conn, tenantId);
                            conn.commit();
                        }
                    }
                    """;
            assertThat(CALL_PREREQUISITES.matcher(withCall).find())
                    .as("加上调用后必须命中")
                    .isTrue();
        }

        @Test
        @DisplayName("⚠️⚠️ 读点换成 currentOrInitialize 必须被检出")
        void detectsReadSideDowngrade() {
            String downgraded = """
                    class Downgraded {
                        long read() {
                            return authzVersions.currentOrInitialize(tenantId, principalId)
                                    .authzVersion();
                        }
                    }
                    """;
            assertThat(READ_SIDE_INIT.matcher(downgraded).find()).isTrue();
            assertThat(STRICT_READ.matcher(downgraded).find()).isFalse();
        }

        @Test
        @DisplayName("✅ 注释里出现 currentOrInitialize 不得误报")
        void commentsAreIgnored() {
            String withCommentOnly = """
                    class Ok {
                        // ⚠️ 不得用 currentOrInitialize（缺行补建 = 把从未授权当成已授权）。
                        long authzVersion = authzVersions.require(tenantId, principalId).authzVersion();
                    }
                    """;
            String stripped = stripComments(withCommentOnly);
            assertThat(READ_SIDE_INIT.matcher(stripped).find())
                    .as("注释被剥离后不应残留读时补建信号")
                    .isFalse();
            assertThat(STRICT_READ.matcher(stripped).find()).isTrue();
        }

        @Test
        @DisplayName("✅ 换行 + 点号前缀的 .require( 也要认（ExecutionAdmissionChecker 的真实写法）")
        void strictReadAcceptsLineBreakBeforeDot() {
            String realShape = """
                    currentVersion = authzVersions
                            .require(request.tenantId(), request.principalId())
                            .authzVersion();
                    """;
            assertThat(STRICT_READ.matcher(realShape).find()).isTrue();
        }
    }

    // ── 扫描基础设施 ──────────────────────────────────────────────

    private static int indexOf(String code, Pattern pattern) {
        var matcher = pattern.matcher(code);
        return matcher.find() ? matcher.start() : -1;
    }

    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未定位到 platform 源码根目录（user.dir="
                + System.getProperty("user.dir") + "）");
    }

    private static String readCode(Path file) {
        return stripComments(readRaw(file));
    }

    private static String readRaw(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }

    /** 只去注释、保留字符串与文本块（SQL 与解释性文字都在字符串里）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
