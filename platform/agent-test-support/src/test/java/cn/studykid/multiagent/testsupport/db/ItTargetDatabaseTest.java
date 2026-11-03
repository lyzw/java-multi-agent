package cn.studykid.multiagent.testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ItTargetDatabase} 的判据守卫（无 DB、无环境依赖）。
 *
 * <p>⚠️ 本类里有一半用例是<b>反证</b>：既然判据永远返回「没问题」也能让构建变绿，
 * 就必须证明它在该红的时候真的红。否则守班会退化成「装饰」。
 */
class ItTargetDatabaseTest {

    private static final String OK_URL = "jdbc:postgresql://127.0.0.1:5433/agent_it_fresh";

    @Nested
    @DisplayName("库名解析")
    class Parsing {

        @Test
        @DisplayName("标准形式、带参数、多主机、短形式都能取到库名")
        void extractsDatabaseName() {
            assertThat(ItTargetDatabase.databaseName(OK_URL)).isEqualTo("agent_it_fresh");
            assertThat(ItTargetDatabase.databaseName(OK_URL + "?user=app_agent&password=x"))
                    .isEqualTo("agent_it_fresh");
            assertThat(ItTargetDatabase.databaseName(
                            "jdbc:postgresql://h1:5432,h2:5432/agent_it?sslmode=require"))
                    .isEqualTo("agent_it");
            assertThat(ItTargetDatabase.databaseName("jdbc:postgresql:agent_it"))
                    .isEqualTo("agent_it");
        }

        @Test
        @DisplayName("非 PostgreSQL / 空串 / 无可解析库名 → null（交给调用方判红）")
        void returnsNullWhenUnparsable() {
            assertThat(ItTargetDatabase.databaseName(null)).isNull();
            assertThat(ItTargetDatabase.databaseName("  ")).isNull();
            assertThat(ItTargetDatabase.databaseName("jdbc:mysql://h/db")).isNull();
            assertThat(ItTargetDatabase.databaseName("jdbc:postgresql://host:5432/")).isNull();
        }
    }

    @Nested
    @DisplayName("判据")
    class Judgement {

        @Test
        @DisplayName("✅ 隔离库通过；未设 IT_PG_URL 时也通过（IT 会整批跳过，无风险）")
        void isolatedTargetPasses() {
            assertThat(ItTargetDatabase.violation(OK_URL, "")).isEmpty();
            assertThat(ItTargetDatabase.violation(OK_URL, "jdbc:postgresql://h:5432/agent_dev"))
                    .isEmpty();
            assertThat(ItTargetDatabase.violation(null, "jdbc:postgresql://h:5432/agent_dev"))
                    .isEmpty();
            assertThat(ItTargetDatabase.violation("  ", null)).isEmpty();
        }

        @Test
        @DisplayName("⛔ 硬编码名单：agent_dev / study_rag（大小写不敏感）一律判红")
        void knownApplicationDatabasesAreRejected() {
            for (String db : new String[] {"agent_dev", "AGENT_DEV", "study_rag"}) {
                assertThat(ItTargetDatabase.violation("jdbc:postgresql://h:5432/" + db, ""))
                        .as("db=%s", db)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("⛔ 与 platform.env 声明的应用库同名即判红（应用库改名也挡得住）")
        void sameAsDeclaredApplicationDatabaseIsRejected() {
            String appUrl = "jdbc:postgresql://h:5432/renamed_app_db?user=ai_agent";
            assertThat(ItTargetDatabase.violation("jdbc:postgresql://other:5433/renamed_app_db", appUrl))
                    .as("库名相同即视为同一个库，不依赖硬编码名字")
                    .isPresent()
                    .get()
                    .asString()
                    .contains("SPRING_DATASOURCE_URL");
        }

        @Test
        @DisplayName("⛔ 解析不出库名一律判红（不静默放行未知连接串）")
        void unparsableTargetIsRejected() {
            assertThat(ItTargetDatabase.violation("jdbc:mysql://h:3306/x", "")).isPresent();
            assertThat(ItTargetDatabase.violation("not-a-url", "")).isPresent();
        }

        @Test
        @DisplayName("⛔ 报错信息不得泄露口令")
        void violationMessageMasksPassword() {
            String url = "jdbc:postgresql://h:5432/agent_dev?user=ai_agent&password=sup3rsecret";
            assertThat(ItTargetDatabase.violation(url, ""))
                    .get()
                    .asString()
                    .doesNotContain("sup3rsecret")
                    .contains("password=***");
        }

        @Test
        @DisplayName("⛔ requireIsolated 在违规时抛异常、合规时静默")
        void requireIsolatedThrowsOnlyOnViolation() {
            assertThatCode(() -> ItTargetDatabase.requireIsolated(OK_URL, "")).doesNotThrowAnyException();
            assertThatThrownBy(() ->
                            ItTargetDatabase.requireIsolated("jdbc:postgresql://h:5432/agent_dev", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IT_PG_URL");
        }
    }

    @Nested
    @DisplayName("platform.env 读取")
    class EnvFile {

        @Test
        @DisplayName("读 KEY=VALUE：跳过注释/空行、去掉包裹引号、键名精确匹配")
        void readsKeyValue(@TempDir Path dir) throws Exception {
            Path env = dir.resolve("platform.env");
            java.nio.file.Files.writeString(env, """
                    # 注释行
                    SPRING_DATASOURCE_URL=jdbc:postgresql://h:5432/agent_dev
                    SPRING_DATASOURCE_URL_BACKUP=jdbc:postgresql://h:5432/other
                    QUOTED="jdbc:postgresql://q:5432/qdb"
                    SINGLE='plain'
                    EMPTY=
                    """);

            assertThat(ItTargetDatabase.value(env, "SPRING_DATASOURCE_URL"))
                    .contains("jdbc:postgresql://h:5432/agent_dev");
            assertThat(ItTargetDatabase.value(env, "QUOTED")).contains("jdbc:postgresql://q:5432/qdb");
            assertThat(ItTargetDatabase.value(env, "SINGLE")).contains("plain");
            assertThat(ItTargetDatabase.value(env, "EMPTY")).isEmpty();
            assertThat(ItTargetDatabase.value(env, "NOPE")).isEmpty();
            assertThat(ItTargetDatabase.value(dir.resolve("missing.env"), "X")).isEmpty();
        }

        @Test
        @DisplayName("user.dir 上溯定位：要么找不到（CI 无 platform.env），要么是<b>仓库根</b>下可读的那份")
        void locatesEnvFileByWalkingUp() {
            // platform.env 被 .gitignore 忽略 → CI 上没有此文件属正常，此时只能靠硬编码名单。
            // 但只要找到了，就必须是仓库根（同时含 platform/ 与 deploy/）的那份，
            // 否则说明上溯停在了某个中间目录，交叉比对会拿错连接串。
            ItTargetDatabase.applicationEnvFile().ifPresent(env -> {
                assertThat(env).as("定位到的 platform.env 必须可读").isReadable();
                assertThat(env.getParent().resolve("platform").resolve("pom.xml"))
                        .as("platform.env 应位于仓库根（其下有 platform/pom.xml）")
                        .exists();
            });
        }
    }
}
