package cn.studykid.rag.bootstrap.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 数据库边界守卫（2026-11-06 数据库层收敛）。
 *
 * <p><b>为什么必须有这类守卫</b>：把 JDBC 实现搬进一个模块，编译期不会阻止后来有人在
 * 业务模块里「顺手」加一条 {@code spring-boot-starter-jpa} 或写一个
 * {@code new JdbcTemplate(...)}。边界一旦被突破，症状要等到「换 ORM 时改动面爆炸」
 * 才显现。本类把「所有数据库相关操作只在 {@code rag-persistence}」变成构建期硬约束。
 *
 * <p>可证伪命题：
 * <ol>
 *   <li>数据库 artifact 只出现在 {@code rag-persistence/pom.xml}；</li>
 *   <li>{@code java.sql.*} / {@code javax.sql.*} / 具体 JDBC 实现包只在
 *       {@code rag-persistence/src/main} 被 import；</li>
 *   <li>真正的 ORM 类型（JPA/Hibernate/MyBatis/JOOQ/Liquibase）在任何模块、
 *       包括测试源码中都不出现；连库 IT 的原生 JDBC 夹具是有界例外；</li>
 *   <li>依赖方向：内层模块不依赖数据库模块，只有三个外层入口模块负责接线；
 *       {@code rag-infrastructure} 不得反向依赖（否则 Maven 成环）；</li>
 *   <li>迁移后 Spring 组件扫描入口与关键装配类仍存在，接线未被「搬丢」。</li>
 * </ol>
 *
 * <p>⚠️ 防空转：路径发现失败会让空集合恒绿，本项目踩过多次，故每个扫描都带
 * 「至少扫到 N 个模块 / N 个文件 / 边界模块确有 JDBC 代码」的反向断言。
 */
class RagDatabaseBoundaryTest {

    private static final String DB_MODULE = "rag-persistence";

    /** 数据库相关 artifact：出现即视为「声明了具体持久化技术」。 */
    private static final Set<String> DB_ARTIFACTS = Set.of(
            "spring-boot-starter-jdbc", "spring-boot-starter-data-jpa",
            "spring-boot-starter-data-r2dbc", "spring-boot-starter-flyway",
            "spring-boot-starter-liquibase", "spring-jdbc", "spring-orm",
            "spring-data-jpa", "postgresql", "mysql-connector-j",
            "mariadb-java-client", "flyway-core", "flyway-database-postgresql",
            "liquibase-core", "HikariCP", "hibernate-core",
            "mybatis", "mybatis-spring-boot-starter", "jooq", "r2dbc-postgresql");

    /** 具体 JDBC / 驱动 / 迁移工具包：业务模块 main 一律不得 import。 */
    private static final Set<String> JDBC_IMPORTS = Set.of(
            "java.sql.", "javax.sql.", "org.springframework.jdbc.",
            "com.zaxxer.hikari.", "org.postgresql.", "org.flywaydb.");

    /** 真正的 ORM 包：任何模块、任何 scope 都不得出现。 */
    private static final Set<String> ORM_IMPORTS = Set.of(
            "org.springframework.orm.", "jakarta.persistence.", "org.hibernate.",
            "org.apache.ibatis.", "org.jooq.", "liquibase.");

    /** 允许接线依赖数据库模块的外层入口模块。 */
    private static final Set<String> WIRING_MODULES =
            Set.of("rag-bootstrap", "rag-worker", "rag-management");

    /** 内层模块：只依赖端口契约，不得直接依赖数据库实现。 */
    private static final Set<String> INNER_MODULES = Set.of(
            "rag-core", "rag-ports", "rag-premise", "rag-application",
            "rag-infrastructure", "rag-api");

    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>(.*?)</artifactId>");
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    @Test
    @DisplayName("✅ 数据库依赖只在 rag-persistence 声明")
    void dbDependenciesLiveInOneModule() {
        List<String> violations = new ArrayList<>();
        int[] scanned = {0};
        for (Path pom : modulePoms()) {
            scanned[0]++;
            String module = pom.getParent().getFileName().toString();
            Matcher block = DEPENDENCY_BLOCK.matcher(read(pom));
            while (block.find()) {
                Matcher artifact = ARTIFACT.matcher(block.group(1));
                if (artifact.find()) {
                    String id = artifact.group(1).trim();
                    if (DB_ARTIFACTS.contains(id) && !DB_MODULE.equals(module)) {
                        violations.add(module + " → " + id);
                    }
                }
            }
        }

        assertThat(scanned[0]).as("至少应扫到 10 个子模块 pom，否则是路径发现失败")
                .isGreaterThanOrEqualTo(10);
        assertThat(violations)
                .as("数据库依赖必须集中在 rag-persistence："
                        + "业务模块一旦自己声明 JDBC/JPA/驱动，换 ORM 就不再是单模块改动")
                .isEmpty();

        // 反向断言：白名单不是死代码，边界模块确实声明了数据库技术栈。
        String dbPom = read(ragRoot().resolve(DB_MODULE + "/pom.xml"));
        assertThat(dbPom).contains("spring-boot-starter-jdbc", "postgresql",
                "flyway-core", "spring-boot-starter-flyway");
    }

    @Test
    @DisplayName("✅ 具体 JDBC/ORM 实现包只在 rag-persistence 主源码被引用")
    void concreteJdbcTypesStayInDatabaseModule() {
        List<String> violations = new ArrayList<>();
        int[] files = {0};

        for (Path file : mainJavaFiles()) {
            files[0]++;
            String module = moduleOf(file);
            if (DB_MODULE.equals(module)) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(stripComments(read(file)));
            while (matcher.find()) {
                String imported = matcher.group(1);
                for (String prefix : JDBC_IMPORTS) {
                    if (imported.startsWith(prefix)) {
                        violations.add(module + ": " + rel(file) + " → " + imported);
                    }
                }
                for (String prefix : ORM_IMPORTS) {
                    if (imported.startsWith(prefix)) {
                        violations.add(module + ": " + rel(file) + " → " + imported + "（ORM 实现）");
                    }
                }
            }
        }

        assertThat(files[0]).as("至少应扫到 150 个主源码文件，否则是路径发现失败")
                .isGreaterThanOrEqualTo(150);
        assertThat(violations)
                .as("业务模块不得直接依赖具体持久化实现："
                        + "请通过 rag-ports 的端口契约访问，实现放在 rag-persistence")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 测试侧连库夹具为有界例外，ORM 类型一律不得扩散")
    void ormTypesNeverLeakIntoTests() {
        List<String> violations = new ArrayList<>();
        int[] files = {0};
        int[] knownJdbcFixtures = {0};

        for (Path file : testJavaFiles()) {
            files[0]++;
            String module = moduleOf(file);
            Matcher matcher = IMPORT.matcher(stripComments(read(file)));
            while (matcher.find()) {
                String imported = matcher.group(1);
                for (String prefix : ORM_IMPORTS) {
                    if (imported.startsWith(prefix) && !DB_MODULE.equals(module)) {
                        violations.add(module + ": " + rel(file) + " → " + imported);
                    }
                }
                for (String prefix : JDBC_IMPORTS) {
                    if (imported.startsWith(prefix) && !DB_MODULE.equals(module)) {
                        // 连库 IT 与 Mock 单测需要原生 JDBC 造数，属黑盒验证夹具，
                        // 不经过被测实现；这是刻意保留的有界例外。
                        knownJdbcFixtures[0]++;
                    }
                }
            }
        }

        assertThat(files[0]).as("至少应扫到 70 个测试源码文件，否则是路径发现失败")
                .isGreaterThanOrEqualTo(70);
        assertThat(knownJdbcFixtures[0])
                .as("外层模块的原生 JDBC 造数夹具应确实被识别；"
                        + "若为 0，说明测试目录没被扫到或断言成了死代码")
                .isGreaterThanOrEqualTo(8);
        assertThat(violations)
                .as("连库夹具只允许原生 JDBC；JPA/Hibernate/MyBatis/JOOQ 等"
                        + "真正的 ORM 类型不得出现在任何业务或测试模块")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 外层入口接线、内层模块与 infrastructure 不反向依赖数据库模块")
    void dependencyDirectionIsNotInverted() {
        for (String module : WIRING_MODULES) {
            assertThat(declaresDependency(module, DB_MODULE))
                    .as("%s 必须显式依赖 rag-persistence，否则持久化 Bean 不进运行时 classpath", module)
                    .isTrue();
        }
        List<String> violations = new ArrayList<>();
        for (String module : INNER_MODULES) {
            if (declaresDependency(module, DB_MODULE)) {
                violations.add(module);
            }
        }
        assertThat(violations)
                .as("内层模块只依赖端口契约；尤其 rag-infrastructure 反向依赖会形成 Maven 环")
                .isEmpty();
        assertThat(declaresDependency(DB_MODULE, "rag-infrastructure"))
                .as("数据库模块复用非数据库适配器（分词/嵌入/解析），方向必须保持单向")
                .isTrue();
    }

    @Test
    @DisplayName("✅ 迁移后组件扫描入口与关键装配类仍可被发现（接线未被搬丢）")
    void wiringEntryPointsSurviveModuleSplit() {
        Path root = ragRoot();
        String[] movedConfigs = {
                DB_MODULE + "/src/main/java/cn/studykid/rag/bootstrap/RagInfrastructureConfig.java",
                DB_MODULE + "/src/main/java/cn/studykid/rag/bootstrap/RagStartupSelfCheck.java",
                DB_MODULE + "/src/main/java/cn/studykid/rag/worker/WorkerInfraConfig.java",
                DB_MODULE + "/src/main/java/cn/studykid/rag/worker/ReindexRunner.java",
                DB_MODULE + "/src/main/java/cn/studykid/rag/management/infrastructure/RagManagementConfig.java",
                DB_MODULE + "/src/main/java/cn/studykid/rag/infrastructure/ops/RagOpsConfiguration.java",
        };
        for (String path : movedConfigs) {
            assertThat(Files.isRegularFile(root.resolve(path)))
                    .as("迁移后装配类必须仍存在于新模块：%s", path).isTrue();
        }

        // 包名不变是本次兼容策略的核心：入口的 scanBasePackages 一个字都不用改。
        String app = read(root.resolve(
                "rag-bootstrap/src/main/java/cn/studykid/rag/bootstrap/RagApplication.java"));
        assertThat(app).contains("\"cn.studykid.rag.bootstrap\"",
                "\"cn.studykid.rag.infrastructure\"", "\"cn.studykid.rag.management\"");
        String worker = read(root.resolve(
                "rag-worker/src/main/java/cn/studykid/rag/worker/RagWorkerApplication.java"));
        assertThat(worker).contains("cn.studykid.rag.worker");
    }

    @Test
    @DisplayName("✅ Flyway classpath 位置与 SQL 资源归属保持不变")
    void flywayLocationIsPreserved() {
        assertThat(Files.isDirectory(ragRoot().resolve(
                DB_MODULE + "/src/main/resources/db/migration")))
                .as("db/migration 必须随数据库实现一起迁入（application.yml 的注释与"
                        + " Boot 4.x Flyway starter 都依赖该 classpath 位置）")
                .isTrue();
        // 目录当前为空：Git 不追踪空目录，必须用占位文件把它固定下来，
        // 否则干净检出后本断言与 Flyway 的 classpath 约定都会失效。
        assertThat(Files.isRegularFile(ragRoot().resolve(
                DB_MODULE + "/src/main/resources/db/migration/.gitkeep")))
                .as("空的 db/migration 必须有 .gitkeep，否则新克隆里目录会消失").isTrue();

        List<String> misplaced = new ArrayList<>();
        for (Path file : mainResourceSqlFiles()) {
            if (!DB_MODULE.equals(moduleOf(file))) {
                misplaced.add(rel(file));
            }
        }
        assertThat(misplaced).as("SQL 脚本应随实现一起放在 rag-persistence").isEmpty();

        long jdbcFiles = mainJavaFiles().stream()
                .filter(p -> DB_MODULE.equals(moduleOf(p)))
                .filter(p -> stripComments(read(p)).contains("java.sql.")
                        || stripComments(read(p)).contains("org.springframework.jdbc."))
                .count();
        assertThat(jdbcFiles)
                .as("rag-persistence 必须真的含有 JDBC 实现，否则「全绿」只是空转")
                .isGreaterThan(20);
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static List<Path> modulePoms() {
        List<Path> out = new ArrayList<>();
        for (Path entry : moduleDirectories()) {
            Path pom = entry.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                out.add(pom);
            }
        }
        return out;
    }

    private static List<Path> mainJavaFiles() {
        return filesUnder("src/main/java", ".java");
    }

    private static List<Path> testJavaFiles() {
        return filesUnder("src/test/java", ".java");
    }

    private static List<Path> mainResourceSqlFiles() {
        return filesUnder("src/main/resources", ".sql");
    }

    private static List<Path> filesUnder(String relative, String suffix) {
        List<Path> out = new ArrayList<>();
        for (Path entry : moduleDirectories()) {
            Path dir = entry.resolve(relative);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(dir)) {
                tree.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(suffix))
                        .forEach(out::add);
            } catch (IOException e) {
                throw new IllegalStateException("扫描 " + dir + " 失败", e);
            }
        }
        return out;
    }

    private static List<Path> moduleDirectories() {
        try (Stream<Path> entries = Files.list(ragRoot())) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("pom.xml")))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描模块目录失败", e);
        }
    }

    private static boolean declaresDependency(String module, String artifactId) {
        Path pom = ragRoot().resolve(module).resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        Matcher block = DEPENDENCY_BLOCK.matcher(read(pom));
        while (block.find()) {
            Matcher artifact = ARTIFACT.matcher(block.group(1));
            if (artifact.find() && artifactId.equals(artifact.group(1).trim())) {
                return true;
            }
        }
        return false;
    }

    private static String moduleOf(Path file) {
        return ragRoot().relativize(file.toAbsolutePath()).getName(0).toString();
    }

    private static String rel(Path file) {
        return ragRoot().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    private static String stripComments(String src) {
        return src.replaceAll("/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
    }

    private static Path ragRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("rag-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 rag 根目录（user.dir=" + System.getProperty("user.dir") + "）");
    }
}
