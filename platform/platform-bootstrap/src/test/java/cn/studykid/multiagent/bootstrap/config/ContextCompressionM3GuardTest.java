package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩 <b>S1-M3 收口</b>的跨模块源码守卫（ADR-013 / CH-9 / D48）。
 *
 * <p>⚠️ 本类钉死四件事，任何一件退化都会让「快照写了」变成空转：
 * <ol>
 *   <li>01 终态 DDL 与 07 活库 ALTER 脚本的 {@code context_policy} 列定义逐字同源；
 *       02 / verify 种子、init-db 说明与 it-env 探针同步，71/44/52 三数不因加列改变；</li>
 *   <li>{@code execution_snapshot} 的<b>全部生产 INSERT</b>都写 {@code context_policy}，
 *       且旧直发与 CAP 归档都从同一个 {@link ContextPolicyReader} 归一化实现派生；
 *       ⛔ 不允许任一侧另写 Jackson / 默认值逻辑；</li>
 *   <li>发布期多传的 {@code contextPolicy} 只进快照：{@code run_config}（DEC-9 容量）
 *       与 {@code prompt_config} 口径不变；运行期唯一策略读点登记在
 *       {@code ContextPolicyResolutionSupport}，M7 装配与 M8-a 指纹裁决共用同一次裁决，
 *       ⛔ 不读快照、不在 adapter 内联第二套读源；</li>
 *   <li>配套 {@code scripts/m3-context-policy-counterproof.sh} 的六点撤点、
 *       中断保护、旧报告清理、红/绿判定与 md5 复原三件套齐全。</li>
 * </ol>
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@link Pattern#find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（全串语义，长文件恒真 = 假绿源）。
 */
class ContextCompressionM3GuardTest {

    private static final String DDL = "deploy/sql/platform/01_platform_ddl.sql";
    private static final String UPGRADE =
            "deploy/sql/platform/07_execution_snapshot_context_policy.sql";
    private static final String SEED = "deploy/sql/platform/02_platform_seed.sql";
    private static final String VERIFY_SEED =
            "platform/agent-persistence/src/main/resources/db/migration/verify/V1_seed.sql";
    private static final String INIT_DB = "scripts/init-db.sh";
    private static final String IT_ENV = "scripts/it-env.sh";

    private static final String READER =
            "platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/"
                    + "ContextPolicyReader.java";
    private static final String ARCHIVE =
            "platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/"
                    + "AgentReleaseArchive.java";
    private static final String PORT =
            "platform/agent-ports/src/main/java/cn/studykid/multiagent/ports/persistence/"
                    + "AgentReleaseArchivePort.java";
    private static final String PUBLISH =
            "platform/agent-application/src/main/java/cn/studykid/multiagent/application/cap/"
                    + "ConfigPublishService.java";
    private static final String MANAGE =
            "platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/"
                    + "persistence/JdbcAgentManageRepository.java";
    private static final String REPO =
            "platform/agent-persistence/src/main/java/cn/studykid/multiagent/infrastructure/"
                    + "persistence/cap/JdbcAgentReleaseArchiveRepository.java";
    private static final String IN_MEMORY =
            "platform/agent-application/src/test/java/cn/studykid/multiagent/application/cap/"
                    + "InMemoryAgentReleaseArchive.java";
    private static final String ADAPTER =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "ContextRequestAssemblyAdapter.java";
    private static final String SUPPORT =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "ContextPolicyResolutionSupport.java";
    private static final String POLICY_VERSION_ADAPTER =
            "platform/platform-bootstrap/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "ContextPolicyVersionAdapter.java";
    private static final String COUNTERPROOF = "scripts/m3-context-policy-counterproof.sh";

    /** 与 DDL / 07 脚本逐字比对的列定义（不引用被测实现常量）。 */
    private static final String COLUMN_DDL =
            "context_policy jsonb NOT NULL DEFAULT '{}'::jsonb";

    @Test
    @DisplayName("✅ RV-M3-G1：01 终态 DDL / 07 活库 ALTER / 种子 / 结构探针逐字同源")
    void ddlAndScriptSyncKeepContextPolicyColumn() {
        String ddl = rawRepo(DDL);
        String tableBody = between(ddl, "CREATE TABLE public.execution_snapshot (", ");");
        assertThat(tableBody)
                .as("⛔ 01 终态快照的 execution_snapshot 表体找不到，守卫会空转")
                .isNotEmpty()
                .as("⛔ 01 终态 DDL 缺 context_policy 列定义（列级漂移会让运行期 INSERT 报 column does not exist）")
                .contains(COLUMN_DDL);
        assertThat(ddl)
                .as("⛔ 01 列注释丢失：运维读库无法判断 {} 是「回落默认」而不是「关闭」")
                .contains("COMMENT ON COLUMN public.execution_snapshot.context_policy IS")
                .contains("回落默认")
                .contains("不混入容量字段");

        String upgrade = rawRepo(UPGRADE);
        assertThat(upgrade)
                .as("⛔ 07 活库升级脚本未与 01 终态 DDL 同定义：新库有列、存量库没列")
                .contains("ADD COLUMN IF NOT EXISTS " + COLUMN_DDL + ";")
                .as("⛔ 07 列注释与 01 不一致")
                .contains("COMMENT ON COLUMN public.execution_snapshot.context_policy IS")
                .as("⛔ 07 缺结构断言：加列失败/类型漂移会静默通过")
                .contains("information_schema.columns")
                .contains("RAISE EXCEPTION '升级校验失败")
                .as("⛔ 07 幂等语义丢失：重复执行会失败")
                .contains("IF NOT EXISTS")
                .as("⛔ 07 必须显式声明加列不加表、三数不变")
                .contains("EXPECT_TABLES=71 / EXPECT_RLS_TABLES=44 / EXPECT_POLICIES=52");

        assertThat(countLiteral(rawRepo(SEED), "context_policy"))
                .as("⛔ 02 种子两处 execution_snapshot INSERT 未同步 context_policy")
                .isGreaterThanOrEqualTo(2);
        assertThat(countLiteral(rawRepo(VERIFY_SEED), "context_policy"))
                .as("⛔ verify DB 种子未同步 context_policy")
                .isGreaterThanOrEqualTo(1);
        assertThat(rawRepo(INIT_DB))
                .as("⛔ init-db.sh 未说明加列不加表 / 三数不变")
                .contains("execution_snapshot 新增 context_policy")
                .contains("71 / 44 / 52");
        assertThat(rawRepo(IT_ENV))
                .as("⛔ it-env.sh --probe 缺少列级探针：库结构漂移会被表数假绿掩盖")
                .contains("snapshot_context_policy=")
                .contains("column_name='context_policy'")
                .contains("execution_snapshot.context_policy 列 ${snapshot_context_policy}");
    }

    @Test
    @DisplayName("✅ RV-M3-G2：两个生产 INSERT 都写快照，且唯一派生实现共用 ContextPolicyReader")
    void bothSnapshotWritePathsUseSingleDerivation() {
        List<String> insertFiles = new ArrayList<>();
        List<String> missingColumn = new ArrayList<>();
        Path src = platformRoot().resolve("agent-persistence/src/main/java");
        Pattern insert = Pattern.compile(
                "INSERT\\s+INTO\\s+(public\\.)?execution_snapshot\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        try (Stream<Path> tree = Files.walk(src)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = stripped(file);
                if (!insert.matcher(code).find()) {
                    continue;
                }
                String name = file.getFileName().toString();
                insertFiles.add(name);
                if (!code.contains("context_policy") || !code.contains("ps.setString(4, contextPolicy);")) {
                    missingColumn.add(name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 execution_snapshot 生产 INSERT 失败", e);
        }
        assertThat(insertFiles)
                .as("⛔ 生产 INSERT 白名单与 M3 登记不一致：新增/删除写入口必须同步守卫")
                .containsExactlyInAnyOrder(
                        "JdbcAgentManageRepository.java", "JdbcAgentReleaseArchiveRepository.java");
        assertThat(missingColumn)
                .as("⛔ 生产 INSERT 未写 context_policy 或未绑定第 5 参数：新 Run 快照会空转")
                .isEmpty();

        for (String rel : List.of(MANAGE, REPO)) {
            assertThat(src(rel))
                    .as("⛔ %s 的 INSERT 列/值清单不是六列同口径", rel)
                    .contains("(version_id, agent_config, prompt_config, "
                            + "tool_contracts, run_config,")
                    .contains("context_policy)")
                    .contains("VALUES (?::uuid, ?::jsonb, '{}'::jsonb, "
                            + "'{}'::jsonb, ?::jsonb, ?::jsonb)");
        }

        String reader = src(READER);
        assertThat(reader)
                .as("⛔ ContextPolicyReader 不再暴露唯一快照派生入口")
                .contains("public static @NonNull String snapshotJson(")
                .contains("public static @NonNull String snapshotFromAgentConfig(")
                .as("⛔ 快照派生绕开唯一校验实现：非法策略会静默写成 {}")
                .contains("ContextPolicyParser.parse(plain);")
                .as("⛔ 快照序列化没有键序归一：同一策略会写出不稳定 JSON")
                .contains("return MAPPER.writeValueAsString(new TreeMap<>(plain));")
                .as("⛔ snapshotFromAgentConfig 未把容器键委托给 snapshotJson（出现第二套归一化）")
                .contains("return snapshotJson(");
        assertThat(countLiteral(reader, "ContextPolicyParser.parse(plain);"))
                .as("⛔ fail-closed 校验点不是唯一一处").isEqualTo(1);
        assertThat(countLiteral(reader,
                "return MAPPER.writeValueAsString(new TreeMap<>(plain));"))
                .as("⛔ 规范序列化点不是唯一一处").isEqualTo(1);

        String archive = src(ARCHIVE);
        assertThat(archive)
                .as("⛔ CAP 入口不从真源 generationParams 派生快照")
                .contains("public static String deriveContextPolicy(")
                .contains("return ContextPolicyReader.snapshotJson(agent.generationParams());");
        assertThat(countLiteral(archive, "ContextPolicyReader.snapshotJson("))
                .as("⛔ CAP 快照派生出现第二处实现").isEqualTo(1);

        String publish = src(PUBLISH);
        assertThat(countLiteral(publish, "AgentReleaseArchive.deriveContextPolicy(agent)"))
                .as("⛔ ConfigPublishService 未把派生快照传给归档端口（CAP 入口空转）")
                .isEqualTo(1);

        assertThat(countLiteral(src(MANAGE),
                "ContextPolicyReader.snapshotFromAgentConfig(definition)"))
                .as("⛔ 旧直发入口未从 definition 派生快照（或出现第二套解析）")
                .isEqualTo(1);

        assertThat(src(PORT))
                .as("⛔ 归档端口未显式携带规范快照参数")
                .contains("@NonNull String contextPolicy");
        String inMemory = src(IN_MEMORY);
        assertThat(inMemory)
                .as("⛔ 测试替身未记录 contextPolicy：端口契约变化会被替身吞掉")
                .contains("@NonNull String contextPolicy")
                .contains("definition, agentConfig == null ? \"{}\" : agentConfig, contextPolicy");
    }

    @Test
    @DisplayName("✅ RV-M3-G3（M8-a 升级）：运行期唯一策略读点同源，零快照读源")
    void m7RuntimeStillReadsGenerationParamsOnly() {
        String support = src(SUPPORT);
        String adapter = src(ADAPTER);
        String versionAdapter = src(POLICY_VERSION_ADAPTER);
        String readCall = "ContextPolicyReader.read(definition.get().generationParams())";
        String resolveCall = "ContextPolicyResolutionSupport.resolve(";

        // ① 旧负面覆盖升级：adapter 不再内联读真源，改由唯一支持类读一次。
        assertThat(countLiteral(support, readCall))
                .as("⛔ 运行期唯一策略读点丢失：冻结定义必须只读 cap_agent_def.generationParams")
                .isEqualTo(1);
        assertThat(countLiteral(adapter, readCall))
                .as("⛔ M7 adapter 出现第二套内联读源：与 M8-a 指纹裁决会静默漂移")
                .isZero();
        assertThat(countLiteral(versionAdapter, readCall))
                .as("⛔ M8-a 指纹裁决自造第二套策略读源：装配按 A 版、校验按 B 版会静默失真")
                .isZero();

        // ② 两处调用点必须都走同一个裁决支持类，指纹才与装配态同源。
        assertThat(countLiteral(adapter, resolveCall))
                .as("⛔ M7 统一装配未走唯一裁决支持：policyVersion 会与请求侧校验分叉")
                .isEqualTo(1);
        assertThat(countLiteral(versionAdapter, resolveCall))
                .as("⛔ M8-a 指纹裁决未复用 M7 裁决支持：组合根会制造第二策略真相")
                .isEqualTo(1);
        assertThat(adapter)
                .as("⛔ M7 未把同一次裁决的模型能力传给装配器：窗口预算可能按别的模型算")
                .contains("resolved.capabilities()")
                .contains("EffectivePolicyProjection.toEffectivePolicy(resolved.policy())");
        assertThat(versionAdapter)
                .as("⛔ M8-a 指纹不是从同一次裁决结果派生")
                .contains("resolved.policy().policyVersion()");

        // ③ 全平台运行期只能有一处读 generationParams，且必须落在白名单支持类。
        Map<String, Integer> runtimeReads = runtimeGenerationParamReads();
        assertThat(runtimeReads)
                .as("⛔ 运行期出现白名单外的 generationParams 读点："
                        + "快照/真源或多套裁决实现会静默分叉")
                .containsOnlyKeys("platform-bootstrap/ContextPolicyResolutionSupport.java");
        assertThat(runtimeReads.values()).containsExactly(1);

        // ④ 三处运行期接线都不得读发布快照或调用发布期派生。
        for (String code : List.of(support, adapter, versionAdapter)) {
            assertNoPattern(code, Pattern.quote("context_policy"),
                    "⛔ 运行期装配出现 context_policy 读源：快照与真源会静默分叉");
            assertNoPattern(code, "snapshotJson\\(",
                    "⛔ 运行期装配调用快照派生：发布期 / 运行期职责混线");
            assertNoPattern(code, "snapshotFromAgentConfig\\(",
                    "⛔ 运行期装配调用旧直发快照派生：职责混线");
        }
    }

    /** 扫描 platform 生产源码中 {@code ContextPolicyReader.read(} 的精确调用点。 */
    private static Map<String, Integer> runtimeGenerationParamReads() {
        Map<String, Integer> found = new LinkedHashMap<>();
        try (Stream<Path> modules = Files.list(platformRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.isDirectory(src)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(src)) {
                    for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                        int n = countLiteral(stripped(file), "ContextPolicyReader.read(");
                        if (n > 0) {
                            found.put(module.getFileName() + "/" + file.getFileName(), n);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描运行期 generationParams 读点失败", e);
        }
        return found;
    }

    @Test
    @DisplayName("✅ RV-M3-G4：六点反证、中断保护、红/绿判定与 md5 复原三件套齐全")
    void counterproofScriptKeepsSixPointsAndRestoreDiscipline() {
        String script = rawRepo(COUNTERPROOF);

        assertThat(script)
                .as("⛔ 反证撤点标识缺失：无法证明「撤掉修复点会红」")
                .contains("RV-M3-01")
                .contains("RV-M3-02")
                .contains("RV-M3-03")
                .contains("RV-M3-04")
                .contains("RV-M3-05")
                .contains("RV-M3-06")
                .as("⛔ RV-M3-01 撤点形态（旧直发硬编码 {}）丢失")
                .contains("OLD_MANAGE='        String contextPolicy = "
                        + "ContextPolicyReader.snapshotFromAgentConfig(definition);'")
                .as("⛔ RV-M3-02 撤点形态（CAP 派生硬编码 {}）丢失")
                .contains("OLD_ARCHIVE='        return "
                        + "ContextPolicyReader.snapshotJson(agent.generationParams());'")
                .as("⛔ RV-M3-03 撤点形态（仓储不绑定参数）丢失")
                .contains("OLD_REPO_BIND='            ps.setString(5, contextPolicy);'")
                .as("⛔ RV-M3-04 撤点形态（撤掉 fail-closed 校验）丢失")
                .contains("OLD_READER='        ContextPolicyParser.parse(plain);'")
                .as("⛔ RV-M3-05 撤点形态（07 丢掉 NOT NULL DEFAULT）丢失")
                .contains("ADD COLUMN IF NOT EXISTS context_policy jsonb NOT NULL DEFAULT")
                .as("⛔ RV-M3-06 撤点形态（M7 读源被耦合）丢失")
                .contains("ContextPolicyReader.read(definition.get().generationParams());");

        assertThat(script)
                .as("⛔ 反证必须覆盖两条真库入口 + fail-closed + 两个源码守卫")
                .contains("AgentRepositoryIT#publishVersionSnapshotsContextPolicy")
                .contains("ConfigPublishManagementIT#publishedAgentSnapshotsContextPolicy")
                .contains("ContextPolicyReaderTest#snapshotDerivationIsFailClosed")
                .contains("ContextCompressionM3GuardTest#ddlAndScriptSyncKeepContextPolicyColumn")
                .contains("ContextCompressionM3GuardTest#m7RuntimeStillReadsGenerationParamsOnly")
                .as("⛔ 中断保护缺失：SIGTERM/SIGINT 会残留撤点源码造成静默失真")
                .contains("trap 'restore; exit 130' INT TERM")
                .as("⛔ 未清理旧报告：编译失败时会读到上次报告，制造假红/假绿")
                .contains("rm -f \"${dir}/${cls}.txt\" \"${dir}/TEST-${cls}.xml\"")
                .as("⛔ 红/绿判定锚点缺失")
                .contains("grep -qE 'Tests run: 1, Failures: 1, Errors: 0, Skipped: 0'")
                .contains("grep -qE 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'")
                .as("⛔ 复原三件套缺失（含六文件 md5 一致性校验）")
                .contains("anchor_count")
                .contains("marker_count")
                .contains("md5_all")
                .contains("git diff --check");
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private static String src(String rel) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(rawRepo(rel));
    }

    private static String stripped(Path file) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(
                ContextCompressionS1GuardTest.read(file));
    }

    private static String rawRepo(String rel) {
        return ContextCompressionS1GuardTest.read(repoRoot().resolve(rel));
    }

    private static Path platformRoot() {
        return ContextCompressionS1GuardTest.platformRoot();
    }

    private static Path repoRoot() {
        return ContextCompressionS1GuardTest.repoRoot();
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        if (from < 0) {
            return "";
        }
        int bodyStart = from + start.length();
        int to = text.indexOf(end, bodyStart);
        return to < 0 ? "" : text.substring(bodyStart, to);
    }

    private static int countLiteral(String text, String literal) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(literal, from)) >= 0) {
            count++;
            from += literal.length();
        }
        return count;
    }

    /** 锚点出现即失败（Pattern.find 取反，规避 doesNotMatch 全串假绿）。 */
    private static void assertNoPattern(String text, String regex, String reason) {
        assertThat(Pattern.compile(regex, Pattern.DOTALL).matcher(text).find())
                .as(reason + "（Pattern.find 取反，非全串匹配）")
                .isFalse();
    }
}
