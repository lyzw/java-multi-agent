package cn.studykid.multiagent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P3-1 ④ 结构门禁：<b>租户判据只允许有一处实现</b>。
 *
 * <p>⚠️⚠️ <b>本类存在的理由（一次真实的漂移被发现）</b>：
 * 仓库里 50 条 RLS 策略，49 条用 {@code public.current_tenant_id()} 取租户，
 * 唯独 {@code rate_limit_bucket} 把判据手写重实现了一遍
 * （{@code tenant_id = (current_setting('app.tenant_id'::text, true))::uuid}）。
 * 表面等价，实则两处语义不同：
 * <ul>
 *   <li>函数版带 {@code NULLIF(..., '')} → 空串租户上下文得到 NULL → <b>拒绝访问</b>；</li>
 *   <li>手写版直接 {@code ''::uuid} → <b>invalid input syntax</b> → 500。</li>
 * </ul>
 * 同一错误输入，49 张表是「拒绝」，1 张表是「报错」。
 *
 * <p>⚠️ <b>本类防的是复发，不是那一次</b>：
 * 「同一个判据散落两处，其中一处必然过期」——
 * 将来 {@code current_tenant_id()} 若引入平台作用域，
 * 抄写的那一条会<b>静默地</b>不跟着演进，且没有任何报错。
 * 这正是本项目反复踩到的故障形态，故把它固化成门禁。
 *
 * <p>⚠️ <b>不依赖数据库</b>：只扫 DDL 文本，CI 无库也能跑。
 */
class RlsPolicyAlignmentTest {

    /** 匹配 {@code CREATE POLICY name ON [schema.]name <body>;}。 */
    private static final Pattern POLICY = Pattern.compile(
            "CREATE POLICY\\s+(\\w+)\\s+ON\\s+((?:\\w+\\.)?\\w+)([^;]*);", Pattern.DOTALL);

    /**
     * 「租户隔离」类策略的表征：判据里出现 tenant_id。
     *
     * <p>⚠️ 只扫这类策略：平台目录表（cap_tool_def 等）没有 RLS 是刻意设计，
     * 扫它们会得到一个必须加白名单的假失败，反而稀释门禁信号。
     */
    private static final Pattern MENTIONS_TENANT =
            Pattern.compile("tenant_id", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIRECT_CURRENT_SETTING =
            Pattern.compile("current_setting\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANONICAL_FUNCTION =
            Pattern.compile("current_tenant_id\\s*\\(\\)", Pattern.CASE_INSENSITIVE);

    /**
     * 允许直接读 {@code current_setting} 的对象名。
     *
     * <p>⚠️ 目前为<b>空</b>，且应当保持为空：任何加入这里的条目都是
     * 「一处注定会过期的判据」。真有必要特例时，请连同理由写进这里，
     * 让下一个读代码的人知道这是有意为之而不是漏网。
     */
    private static final List<String> ALLOWED = List.of();

    @Test
    @DisplayName("⛔ 租户隔离策略不得绕过 current_tenant_id() 自行 current_setting（单点真相）")
    void tenantPoliciesMustUseCanonicalFunction() {
        StringBuilder violations = new StringBuilder();
        Matcher m = POLICY.matcher(PlatformDdl.text());
        int scanned = 0;

        while (m.find()) {
            String policy = m.group(1);
            String table = m.group(2);
            String body = m.group(3);

            if (!MENTIONS_TENANT.matcher(body).find()) {
                continue;
            }
            scanned++;
            if (ALLOWED.contains(table)) {
                continue;
            }
            if (DIRECT_CURRENT_SETTING.matcher(body).find()
                    && !CANONICAL_FUNCTION.matcher(body).find()) {
                violations.append("\n - ").append(policy).append(" ON ").append(table);
            }
        }

        assertThat(scanned)
                .as("必须真的扫到租户隔离策略 —— 扫到 0 条说明正则失配，门禁是假的")
                .isGreaterThan(0);
        assertThat(violations.toString())
                .as("租户判据必须统一走 public.current_tenant_id()："
                        + "手写的 current_setting 在空串租户上下文下会报 500 而非拒绝访问，"
                        + "且将来函数演进时这条不会跟着变（静默漂移）。违规策略：")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 快照必须真的定义 current_tenant_id()（否则上一条断言只是空跑）")
    void canonicalFunctionMustExist() {
        assertThat(PlatformDdl.text())
                .as("current_tenant_id() 是全部租户隔离策略的唯一真相源，缺失则门禁无从谈起")
                .contains("FUNCTION public.current_tenant_id()");
    }
}
