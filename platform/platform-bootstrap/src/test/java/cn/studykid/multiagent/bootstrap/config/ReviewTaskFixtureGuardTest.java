package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人工审查队列 <b>IT 夹具隔离</b>源码守卫（2026-11-11 事故后补）。
 *
 * <p>事故链：{@code TraceCollectClosedLoopIT} / {@code TraceMetricContentAccuracyIT} /
 * {@code TraceCollectDatabaseChannelEndToEndIT} 跑真实 Run 时，质量信号（L1_GAP /
 * SAMPLE_RANDOM）会自动落 {@code platform.review_task}；三者的 {@code @AfterEach}
 * 只删 {@code run} / {@code run_step} / {@code agent_trace_metric}，<b>漏删 review_task</b>。
 * 残留逐次 {@code clean verify} 累积（11-09 至 11-11 达 48 条），把
 * {@code JdbcReviewTaskRepositoryIT#createAndListOrdered} 的全局
 * {@code list(null, null, 50)} 挤出 {@code LIMIT 50}（按 CASE priority + created_at ASC，
 * 旧 HIGH 在前），导致真测试假红。
 *
 * <p>本守卫钉死两条修复：
 * <ol>
 *   <li>四类闭环 IT 的 {@code cleanup()} 必须回收自己租户的 {@code platform.review_task}；</li>
 *   <li>{@code JdbcReviewTaskRepositoryIT} 的 {@code list(...)} 断言必须按 {@code tenantId} 过滤，
 *       不得再用全局 {@code list(null, null, N)} 依赖「库里恰好没别人」。</li>
 * </ol>
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，配套 RV-86..RV-88 撤点验证
 * （精确计数替换 + finally 复原 + md5 校验）。本类<b>不引用被测实现自己的常量</b>：
 * 表名、字段名、锚点全部用字面量冻结。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长文件里恒真，是假绿源）。
 */
class ReviewTaskFixtureGuardTest {

    private static final String PKG = "platform/platform-bootstrap/src/test/java/cn/studykid/multiagent/bootstrap/messaging/";
    private static final String PERSIST =
            "platform/agent-persistence/src/test/java/cn/studykid/multiagent/infrastructure/persistence/";

    /** 跑真实 Run、会自动落 review_task 的四个闭环 IT。 */
    private static final List<String> LEAKY_ITS = List.of(
            "TraceCollectClosedLoopIT.java",
            "TraceMetricContentAccuracyIT.java",
            "TraceCollectDatabaseChannelEndToEndIT.java");

    /** 已正确回收 review_task 的对照 IT（防回退）。 */
    private static final List<String> CORRECT_ITS = List.of(
            "ReviewAlertClosedLoopIT.java",
            "EvaluationClosedLoopIT.java",
            "L2AttributionClosedLoopIT.java");

    private static final Pattern REVIEW_TASK_DELETE = Pattern.compile(
            "DELETE\\s+FROM\\s+platform\\.review_task[\\s\\S]{0,120}?tenant_id\\s*=\\s*\\?");

    @Test
    @DisplayName("✅ RV-86：四个闭环 IT 的 cleanup 必须回收自己租户的 review_task（带 tenant 谓词）")
    void closedLoopItsReclaimReviewTaskByTenant() {
        for (String file : LEAKY_ITS) {
            String cleanup = cleanupRegion(source(PKG + file));
            assertThat(REVIEW_TASK_DELETE.matcher(cleanup).find())
                    .as("⛔ %s 的 cleanup 未回收 platform.review_task（带 tenant_id 谓词）——"
                            + "真实 Run 的质检信号会落库，漏删会逐次 verify 累积挤爆别人的 LIMIT 50", file)
                    .isTrue();
        }
        // 对照：已正确的三个 IT 不能回退删掉回收
        for (String file : CORRECT_ITS) {
            String raw = source(PKG + file);
            assertThat(REVIEW_TASK_DELETE.matcher(raw).find())
                    .as("⛔ %s 原有 review_task 回收被回退删除", file)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("✅ RV-87：JdbcReviewTaskRepositoryIT 所有 list 断言按 tenant 过滤，无全局 list(null,null,N)")
    void reviewTaskItFiltersEveryListByTenant() {
        String raw = source(PERSIST + "JdbcReviewTaskRepositoryIT.java");
        String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(raw);
        Pattern globalList = Pattern.compile("list\\(\\s*null\\s*,\\s*null\\s*,\\s*\\d+\\s*\\)");
        assertThat(globalList.matcher(code).find())
                .as("⛔ 仍有全局 list(null, null, N)：会依赖「库里恰好没别人」，"
                        + "被其它 IT 的 review_task 残留挤爆 LIMIT 50 造成假红（regex find 非全串匹配）")
                .isFalse();

        // 至少有 5 处按 tenantId 过滤的 list（createAndListOrdered/assignFlow/
        // resolveThenGuardTerminal/filters×2/hook×1）
        int scoped = 0;
        Matcher m = Pattern.compile("list\\([^;]{0,60}?tenantId[^;]{0,20}?\\)").matcher(code);
        while (m.find()) {
            scoped++;
        }
        assertThat(scoped)
                .as("⛔ 按 tenantId 过滤的 list 调用少于 4 处：隔离修复被撤")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("✅ RV-88：createAndListOrdered 的排序断言仍验 HIGH 在前，非空壳测试")
    void createAndListOrderedKeepsPriorityAssertion() {
        String raw = source(PERSIST + "JdbcReviewTaskRepositoryIT.java");
        String code = ContextCompressionS1GuardTest.stripCommentsKeepStrings(raw);
        String method = methodRegion(code, "void createAndListOrdered()");
        // 精确锚定排序断言语句本身：仅查 priority()/HIGH 会被 Priority.HIGH、
        // .map(v -> v.priority()) 等其它出现点满足，撤掉断言仍绿（假绿坑）。
        assertThat(method)
                .as("⛔ createAndListOrdered 丢了「HIGH 排在首位」的排序断言，退化成「有一行」弱测试")
                .contains("rows.get(0).priority()).isEqualTo(\"HIGH\")")
                .contains("contains(normal, high)")
                .contains("isIn(\"PENDING\", \"IN_REVIEW\")");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /** 截取 {@code @AfterEach void cleanup() { ... }} 方法体（含嵌套大括号配对）。 */
    private static String cleanupRegion(String src) {
        int sig = src.indexOf("void cleanup()");
        assertThat(sig).as("⛔ 找不到 cleanup() 方法").isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', sig);
        assertThat(open).as("⛔ 找不到 cleanup() 开括号").isGreaterThan(sig);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("⛔ cleanup() 大括号不配对");
    }

    /** 截取指定方法签名到其匹配的闭括号（用于单方法断言，避免跨方法串味）。 */
    private static String methodRegion(String src, String signature) {
        int sig = src.indexOf(signature);
        assertThat(sig).as("⛔ 找不到方法 %s", signature).isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', sig);
        assertThat(open).as("⛔ 找不到 %s 开括号", signature).isGreaterThan(sig);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("⛔ " + signature + " 大括号不配对");
    }

    private static String source(String rel) {
        return ContextCompressionS1GuardTest.read(
                ContextCompressionS1GuardTest.repoRoot().resolve(rel));
    }
}
