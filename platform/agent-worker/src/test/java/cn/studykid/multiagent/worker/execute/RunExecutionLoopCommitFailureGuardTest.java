package cn.studykid.multiagent.worker.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code commitFailure} 双调用与矛盾失败事件的静态守卫（P0 修复 · 2026-11-15）。
 *
 * <p><b>要钉死的缺陷</b>：多个失败分支曾写成
 * <pre>
 * notifyLoopComplete(..., commitFailure(...), steps);
 * return commitFailure(...);
 * </pre>
 * 第一次 CAS 成功把 Run 收敛为 FAILED，第二次必然因世代已变而失败，
 * 于是 {@code return} 拿到 {@code skippedStaleGeneration()} ——
 * 上层把「超时失败」误判为「陈旧世代跳过」，且失败事件被写两遍。
 *
 * <p>另有若干超时分支在 {@code commitFailure} 之外另发一条
 * {@code {"state":"TIMED_OUT"}} 的 RUN_FAILED 事件，与 {@code commitFailure}
 * 内部统一写的 {@code {"state":"FAILED"}} 互相矛盾；且 TIMED_OUT 根本不是
 * {@code public.run_state} 的合法值。本次一并收口。
 *
 * <p><b>反向验证</b>：把任一分支改回「两次 commitFailure」或加回
 * {@code state=TIMED_OUT} 事件，本测试立即变红。
 *
 * <p>⚠️ 所有「不得出现某模式」断言用 {@link Pattern#find()} 取反或
 * {@code doesNotContain}，且先剥注释保留字符串字面量 ——
 * 避免把修复说明里的文字误判成代码。
 */
class RunExecutionLoopCommitFailureGuardTest {

    private static final String SRC =
            "platform/agent-worker/src/main/java/cn/studykid/multiagent/worker/execute/"
                    + "RunExecutionLoop.java";

    private static String stripped() {
        return stripCommentsKeepStrings(read(SRC));
    }

    @Test
    @DisplayName("✅ 任何 notifyLoopComplete 的参数里都不得直接再调 commitFailure")
    void noDoubleCommitFailureInNotification() {
        String src = stripped();
        // [^;] 把匹配限制在同一条语句内，避免跨到下一分支造成假红。
        Matcher doubleCall = Pattern.compile(
                "notifyLoopComplete\\([^;]{0,400}?commitFailure\\(", Pattern.DOTALL)
                .matcher(src);
        assertThat(doubleCall.find())
                .as("检测到 notifyLoopComplete(…commitFailure(…))："
                        + "这是双调用形态，第二次 CAS 必然失败并误记结局")
                .isFalse();
    }

    @Test
    @DisplayName("✅ 每个失败结局都只调用一次 commitFailure 并复用同一对象")
    void everyFailureReusesSingleOutcome() {
        String src = stripped();
        Matcher assignments = Pattern.compile(
                "ExecutionOutcome\\s+\\w+\\s*=\\s*commitFailure\\(")
                .matcher(src);
        int n = 0;
        while (assignments.find()) {
            n++;
        }
        assertThat(n)
                .as("改用局部变量复用结局的失败分支至少应有 4 处；"
                        + "数量骤减说明修复被回退")
                .isGreaterThanOrEqualTo(4);

        assertThat(src)
                .as("失败结局必须来自同一个 ExecutionOutcome 对象")
                .contains("notifyLoopComplete(tenantId, task.runId(), rootTimedOut, steps)")
                .contains("notifyLoopComplete(tenantId, task.runId(), maxSteps, steps)");
    }

    @Test
    @DisplayName("✅ 失败事件只有 commitFailure 一个来源，且 state 恒为 FAILED")
    void failedEventHasSingleSourceAndLegalState() {
        String src = stripped();
        assertThat(src)
                .as("TIMED_OUT 不是 public.run_state 取值；超时一律 FAILED + error_code=TIMEOUT")
                .doesNotContain("\"state\", \"TIMED_OUT\"");
        assertThat(src)
                .as("commitFailure 仍是唯一写失败终态事件的地方")
                .contains("RunEventPort.Types.RUN_FAILED")
                .contains("java.util.Map.of(\"state\", \"FAILED\")");
    }

    /** 剥掉 // 与 / * * / 注释，但保留字符串字面量（含文本块），避免误伤断言材料。 */
    private static String stripCommentsKeepStrings(String src) {
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
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int close = src.indexOf("\"\"\"", i + 3);
                if (close < 0) {
                    out.append(src, i, n);
                    break;
                }
                out.append(src, i, close + 3);
                i = close + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                int start = i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append(src, start, i);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String read(String relative) {
        try {
            return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取执行循环源码失败: " + relative, e);
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
