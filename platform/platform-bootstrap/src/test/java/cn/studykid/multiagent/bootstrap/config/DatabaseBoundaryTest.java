package cn.studykid.multiagent.bootstrap.config;

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
 * 数据库边界守卫（2026-11-06 数据库层收敛）。
 *
 * <p><b>为什么必须有这类守卫</b>：把 JDBC 实现搬进一个模块，编译期不会阻止
 * 后来有人在业务模块里「顺手」写一个 {@code new JdbcTemplate(...)} 或加一条
 * {@code spring-boot-starter-jpa} 依赖。边界是靠人记住的约定，一旦被突破，
 * 症状要等到「换 ORM 时改动面爆炸」才显现，且那时已无法低成本回退。
 * 本类把「所有数据库相关操作只在 {@code agent-persistence}」变成构建期硬约束。
 *
 * <p>可证伪命题：
 * <ol>
 *   <li>数据库 artifact 只出现在 {@code agent-persistence/pom.xml}；</li>
 *   <li>具体 ORM / JDBC 实现包只在 {@code agent-persistence/src/main} 被 import；
 *       测试侧同样扫描，仅放行「组合根端点 IT 的原生 JDBC 造数夹具」这一处有界例外；</li>
 *   <li>{@code java.sql.*} 只允许在数据库模块与测试造数支撑件；
 *       {@code javax.sql.DataSource} 是 JDK 标准 SPI，额外放行组合根（装配入参）；</li>
 *   <li>业务模块不得直接依赖 {@code agent-persistence}，
 *       {@code agent-infrastructure} 也不得反向依赖（否则形成 Maven 环）；</li>
 *   <li>SQL 资源只集中在数据库模块。</li>
 * </ol>
 *
 * <p>⚠️ 防空转：路径发现失败会让空集合恒绿，本项目踩过多次，故每个扫描都带
 * 「至少扫到 N 个模块 / N 个文件 / 边界模块确有 JDBC 代码」的反向断言。
 */
class DatabaseBoundaryTest {

    private static final String DB_MODULE = "agent-persistence";

    /** 数据库相关 artifact：出现即视为「声明了具体持久化技术」。 */
    private static final Set<String> DB_ARTIFACTS = Set.of(
            "spring-boot-starter-jdbc", "spring-boot-starter-data-jpa",
            "spring-boot-starter-data-r2dbc", "spring-boot-starter-flyway",
            "spring-boot-starter-liquibase", "spring-jdbc", "spring-orm",
            "spring-data-jpa", "postgresql", "mysql-connector-j",
            "mariadb-java-client", "flyway-core", "flyway-database-postgresql",
            "liquibase-core", "HikariCP", "hibernate-core",
            "mybatis", "mybatis-spring-boot-starter", "jooq", "r2dbc-postgresql");

    /** 具体 ORM / JDBC 实现包：业务模块一律不得 import。 */
    private static final Set<String> FORBIDDEN_IMPORTS = Set.of(
            "org.springframework.jdbc.", "org.springframework.orm.",
            "com.zaxxer.hikari.", "org.postgresql.", "org.flywaydb.",
            "jakarta.persistence.", "org.hibernate.", "org.apache.ibatis.",
            "org.jooq.", "liquibase.");

    /**
     * 允许 {@code java.sql.*} 的模块（唯一的例外是 IT 造数支撑件）。
     * 它只用 JDK 自带 JDBC 造 it- 前缀租户，属测试基础设施，不引入任何 ORM 依赖；
     * 刻意不复制第二份实现，避免租户回收纪律分裂成两套真相。
     */
    private static final Set<String> JAVA_SQL_EXEMPT = Set.of(DB_MODULE, "agent-test-support");

    /** 允许 {@code javax.sql.DataSource} 的模块：数据库模块 + 造数件 + 组合根。 */
    private static final Set<String> DATASOURCE_EXEMPT =
            Set.of(DB_MODULE, "agent-test-support", "platform-bootstrap");

    /**
     * 测试侧唯一有界例外：组合根端点 IT 需要真实连库造数/回收，直接用原生 JDBC 夹具
     * （Hikari 连接池 + JdbcTemplate）而不经过被测代码，属黑盒验证，不是业务依赖。
     *
     * <p>⚠️ 这里刻意只放行「连接池 + Spring JDBC」两类，且仅限组合根的 {@code src/test}：
     * 任何 JPA/MyBatis/Hibernate/JOOQ 等真正的 ORM 类型，即便在测试里也一律禁止。
     * 换 ORM 时这些夹具与被测实现无关（PG 库、原生 SQL 不变），但会暴露出一处
     * 需要保留 test 作用域驱动的兼容点，见交付说明。
     */
    private static final Set<String> TEST_FIXTURE_ALLOWED_PREFIXES =
            Set.of("com.zaxxer.hikari.", "org.springframework.jdbc.");

    /** 允许持有原生 JDBC 夹具的模块：数据库模块自身、造数支撑件。 */
    private static final Set<String> TEST_SCOPE_EXEMPT_MODULES =
            Set.of(DB_MODULE, "agent-test-support");

    /** 业务模块：不得直接依赖数据库实现模块（组合根除外）。 */
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "agent-core", "agent-routing", "agent-ports", "agent-application",
            "agent-infrastructure", "agent-memory", "agent-infra",
            "agent-worker", "agent-api");

    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern ARTIFACT =
            Pattern.compile("<artifactId>(.*?)</artifactId>");
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    @Test
    @DisplayName("✅ 数据库依赖只在 agent-persistence 声明")
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

        assertThat(scanned[0]).as("至少应扫到 12 个子模块 pom，否则是路径发现失败")
                .isGreaterThanOrEqualTo(12);
        assertThat(violations)
                .as("数据库依赖必须集中在 agent-persistence："
                        + "业务模块一旦自己声明 JDBC/JPA/驱动，换 ORM 就不再是单模块改动")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 具体 ORM/JDBC 实现包只在 agent-persistence 被引用")
    void concreteOrmTypesStayInDatabaseModule() {
        List<String> violations = new ArrayList<>();
        int[] files = {0};

        for (Path file : mainJavaFiles()) {
            files[0]++;
            String module = moduleOf(file);
            if (DB_MODULE.equals(module)) {
                continue;
            }
            String code = stripComments(read(file));
            Matcher matcher = IMPORT.matcher(code);
            while (matcher.find()) {
                String imported = matcher.group(1);
                for (String prefix : FORBIDDEN_IMPORTS) {
                    if (imported.startsWith(prefix)) {
                        violations.add(module + ": " + imported);
                    }
                }
                if (imported.startsWith("java.sql.") && !JAVA_SQL_EXEMPT.contains(module)) {
                    violations.add(module + ": " + imported + "（JDK JDBC API 只允许造数件/数据库模块）");
                }
                if (imported.startsWith("javax.sql.") && !DATASOURCE_EXEMPT.contains(module)) {
                    violations.add(module + ": " + imported);
                }
            }
        }

        assertThat(files[0]).as("至少应扫到 300 个主源码文件，否则是路径发现失败")
                .isGreaterThanOrEqualTo(300);
        assertThat(violations)
                .as("业务模块不得直接依赖具体 ORM 实现："
                        + "请通过 agent-ports 的端口契约访问，实现放在 agent-persistence")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 测试侧也不得把具体 ORM 扩散到业务模块（原生 JDBC 造数夹具为有界例外）")
    void concreteOrmTypesDoNotLeakIntoBusinessTests() {
        List<String> violations = new ArrayList<>();
        int[] files = {0};
        int[] knownFixtures = {0};

        for (Path file : testJavaFiles()) {
            files[0]++;
            String module = moduleOf(file);
            if (TEST_SCOPE_EXEMPT_MODULES.contains(module)) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(stripComments(read(file)));
            while (matcher.find()) {
                String imported = matcher.group(1);
                for (String prefix : FORBIDDEN_IMPORTS) {
                    if (!imported.startsWith(prefix)) {
                        continue;
                    }
                    boolean bootstrapFixture = "platform-bootstrap".equals(module)
                            && TEST_FIXTURE_ALLOWED_PREFIXES.contains(prefix);
                    if (bootstrapFixture) {
                        knownFixtures[0]++;
                    } else {
                        violations.add(module + ": " + rel(file) + " → " + imported);
                    }
                }
            }
        }

        // ⚠️ 防空转：既要有足够多的测试文件被扫到，也要确实命中已知夹具，
        //    否则「全绿」可能只是因为目录发现失败或夹具已被悄悄删掉。
        assertThat(files[0]).as("至少应扫到 150 个测试源码文件，否则是路径发现失败")
                .isGreaterThanOrEqualTo(150);
        assertThat(knownFixtures[0])
                .as("组合根端点 IT 的原生 JDBC 造数夹具应确实被识别；"
                        + "若为 0，说明白名单成了死代码或 test 目录没被扫到")
                .isGreaterThanOrEqualTo(10);
        assertThat(violations)
                .as("业务模块的测试代码也不得直接依赖具体 ORM 实现；"
                        + "连库造数请用 agent-test-support / agent-persistence 的共享夹具，"
                        + "不要在业务模块里 new JdbcTemplate / 引 JPA 注解")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 组合根接线、内层模块不反向依赖数据库模块")
    void dependencyDirectionIsNotInverted() {
        assertThat(declaresDependency("platform-bootstrap", DB_MODULE))
                .as("组合根必须显式依赖 agent-persistence，否则持久化 Bean 不进运行时 classpath")
                .isTrue();
        assertThat(declaresDependency("agent-infrastructure", DB_MODULE))
                .as("agent-infrastructure 反向依赖数据库模块会形成 Maven 环")
                .isFalse();

        List<String> violations = new ArrayList<>();
        for (String module : BUSINESS_MODULES) {
            if (declaresDependency(module, DB_MODULE)) {
                violations.add(module);
            }
        }
        assertThat(violations)
                .as("业务模块只依赖端口契约；直接依赖实现模块会让 ORM 更换扩散到业务层")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ SQL 资源集中在数据库模块（守卫本身不空转）")
    void sqlResourcesAreCentralizedAndGuardIsLive() {
        List<String> misplaced = new ArrayList<>();
        for (Path file : mainResourceSqlFiles()) {
            if (!DB_MODULE.equals(moduleOf(file))) {
                misplaced.add(rel(file));
            }
        }
        assertThat(misplaced).as("SQL 脚本应随实现一起放在 agent-persistence").isEmpty();

        // ⚠️ 反向断言：数据库模块里确实存在 JDBC 代码与 SQL 资源，
        //    否则「全绿」可能只是因为文件根本没被扫到。
        long jdbcFiles = mainJavaFiles().stream()
                .filter(p -> DB_MODULE.equals(moduleOf(p)))
                .filter(p -> stripComments(read(p)).contains("java.sql."))
                .count();
        assertThat(jdbcFiles).as("agent-persistence 必须真的含有 JDBC 实现").isGreaterThan(20);
        assertThat(Files.isDirectory(platformRoot().resolve(DB_MODULE + "/src/main/resources/db")))
                .as("数据库模块必须持有 schema 校验脚本").isTrue();
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static List<Path> modulePoms() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(platformRoot())) {
            for (Path entry : entries.filter(Files::isDirectory).toList()) {
                Path pom = entry.resolve("pom.xml");
                if (Files.isRegularFile(pom)) {
                    out.add(pom);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描模块 pom 失败", e);
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
        try (Stream<Path> entries = Files.list(platformRoot())) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> !"target".equals(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描模块目录失败", e);
        }
    }

    private static boolean declaresDependency(String module, String artifactId) {
        Path pom = platformRoot().resolve(module).resolve("pom.xml");
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
        return platformRoot().relativize(file.toAbsolutePath()).getName(0).toString();
    }

    private static String rel(Path file) {
        return platformRoot().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
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

    private static Path platformRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("agent-core")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 platform 根目录（user.dir=" + System.getProperty("user.dir") + "）");
    }
}
