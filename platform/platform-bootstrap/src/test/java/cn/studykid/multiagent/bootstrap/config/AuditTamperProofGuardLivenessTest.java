package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审计防篡改守卫的<b>启动期活性</b>守卫（P0 修复 · 2026-11-15）。
 *
 * <p><b>与既有 {@link AuditTamperProofGuardTest} 的分工</b>：那个类只测纯函数
 * {@code assess}，因此当 {@code GUARDED_TABLES} 被注释成空数组、
 * {@code run()} 的循环零次执行时，它<b>依然全绿</b> —— 这正是当时的故障形态。
 * 本类改为断言「清单非空 + 循环真的会跑 + 空清单会 fail-fast」。
 *
 * <p><b>反向验证</b>：把 {@code GUARDED_TABLES} 两行再注释掉，
 * 第 1、2 个用例立即变红；删掉 {@code run()} 开头的 fail-fast，第 3 个变红。
 */
class AuditTamperProofGuardLivenessTest {

    private static final String GUARD_SRC =
            "platform/agent-persistence/src/main/java/cn/studykid/multiagent/bootstrap/config/"
                    + "AuditTamperProofGuard.java";

    @Test
    @DisplayName("✅ 受检表清单非空且正是审计行 + 删除凭证")
    void guardedTableListIsReal() throws Exception {
        assertThat(AuditTamperProofGuard.guardedTableCount()).isEqualTo(2);

        Field field = AuditTamperProofGuard.class.getDeclaredField("GUARDED_TABLES");
        field.setAccessible(true);
        String[] tables = (String[]) field.get(null);
        assertThat(tables)
                .as("清单为空时 run() 的循环零次执行，启动日志全空，守卫形同虚设")
                .containsExactly("audit_record", "erasure_certificate");
    }

    @Test
    @DisplayName("✅ run() 必须真的遍历清单并留下「执行过」的证据")
    void guardActuallyIteratesAndLogs() {
        String src = read(GUARD_SRC);
        assertThat(src)
                .contains("for (String table : GUARDED_TABLES)")
                .as("没有这条收尾日志，就无法区分『全部跳过』和『一次都没跑』")
                .contains("审计凭据隔离校验完成：受检 {} 张表");
    }

    @Test
    @DisplayName("✅ 空清单必须 fail-fast，且真实 run() 在有效清单下不抛异常")
    void emptyListFailsFastAndRealRunSucceeds() {
        String src = read(GUARD_SRC);
        assertThat(src)
                .as("撤掉这段 fail-fast，把清单注释空又变回静默退化")
                .contains("if (GUARDED_TABLES.length == 0)")
                .contains("throw new IllegalStateException(");

        // 用真实 Bean 跑一次启动方法（而非纯函数）。JdbcTemplate 未 stub ⇒ 查询返回 null，
        // 走 fail-closed 的「无法判定」分支；重点是循环确实被执行、且不会因空清单炸掉。
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThatCode(() -> new AuditTamperProofGuard(jdbc, false)
                .run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
    }

    private static String read(String relative) {
        try {
            return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取守卫源码失败: " + relative, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("deploy/sql/platform/01_platform_ddl.sql"))) {
                return dir;
            }
        }
        throw new IllegalStateException("找不到仓库根（deploy/sql/platform/01_platform_ddl.sql）");
    }
}
