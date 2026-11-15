package cn.studykid.multiagent.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.infrastructure.PlatformDdl;

/**
 * DDL 头注释里「RLS 开启 / RLS 策略」计数的一致性守卫（P0 修复 · 2026-11-15）。
 *
 * <p><b>为什么需要</b>：头注释曾长期写「42 RLS 开启 / 50 RLS 策略」，而文件实计
 * 已是 44 / 52。这个数字是评审时判断「隔离覆盖有没有账面盲区」的第一手依据，
 * 漂移后没人会发现——因为没有任何代码读它。这正是本项目反复吃亏的形态：
 * <b>文档里的数字没有执行者</b>。
 *
 * <p><b>反向验证</b>：把头注释改回 42/50，或往 DDL 里加一条 {@code CREATE POLICY}
 * 却不同步头注释，本测试立即变红。
 *
 * <p><b>刻意只校验这三项</b>（表数 / RLS 开启数 / 策略数）：它们的口径明确，
 * 且与 {@code scripts/it-env.sh --probe} 的期望值交叉一致。头注释其余数字
 * （680 列 / 255 约束 / 172 索引 / 33 触发器）来自 pg_dump 的统计口径，
 * 含约束隐式生成的对象，不能与 {@code grep} 行数直接比对；
 * 强行校验只会制造恒定假红，反而让人忽略真正的漂移。
 */
class PlatformDdlHeaderCountTest {

    /** 只匹配形如「44 RLS 开启 / 52 RLS 策略」的快照统计行。 */
    private static final Pattern HEADER_COUNTS = Pattern.compile(
            "(\\d+)\\s+RLS\\s*开启\\s*/\\s*(\\d+)\\s+RLS\\s*策略");

    /** 只匹配形如「71 表 / 680 列」的统计行（要求后接「列」，避开更正说明里的旧值）。 */
    private static final Pattern HEADER_TABLES = Pattern.compile(
            "(\\d+)\\s+表\\s*/\\s*\\d+\\s+列");

    /**
     * 统计<b>真实 SQL 语句</b>的首行，跳过 {@code --} 注释行。
     *
     * <p>⚠️ 必须跳注释：本文件头注释里为了说明修复经过，写了
     * 「ALTER TABLE … ENABLE ROW LEVEL SECURITY = 44」「CREATE POLICY = 52」，
     * 若直接全文数字符串会把自己也算进去，守卫变成假绿。
     */
    private static int statementCount(String prefix, String marker, String excluded) {
        int n = 0;
        for (String line : PlatformDdl.text().split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            // NO FORCE ROW LEVEL SECURITY 也含 "FORCE ROW LEVEL SECURITY" 子串，
            // 必须排除，否则会把「关闭 FORCE」误计成「开启 FORCE」。
            if (excluded != null && trimmed.contains(excluded)) {
                continue;
            }
            if (trimmed.startsWith(prefix) && trimmed.contains(marker)) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("✅ 头注释的 RLS 开启/策略数字必须等于文件实计值")
    void headerCountsMatchFile() {
        String text = PlatformDdl.text();
        Matcher m = HEADER_COUNTS.matcher(text);
        assertThat(m.find())
                .as("DDL 头注释缺少「N RLS 开启 / M RLS 策略」统计行，守卫无从校验")
                .isTrue();

        int declaredEnable = Integer.parseInt(m.group(1));
        int declaredPolicy = Integer.parseInt(m.group(2));

        Matcher t = HEADER_TABLES.matcher(text);
        assertThat(t.find())
                .as("DDL 头注释缺少「N 表 / M 列」统计行")
                .isTrue();
        int declaredTables = Integer.parseInt(t.group(1));

        int actualEnable = statementCount("ALTER TABLE", "ENABLE ROW LEVEL SECURITY", null);
        int actualForce = statementCount("ALTER TABLE", "FORCE ROW LEVEL SECURITY", "NO FORCE");
        int actualPolicy = statementCount("CREATE POLICY", "CREATE POLICY", null);
        int actualTables = PlatformDdl.createdTables().size();

        assertThat(declaredTables)
                .as("头注释表数（%d）与文件实计 CREATE TABLE 数不一致；"
                        + "该值也应与 scripts/it-env.sh --probe 的期望值一致", declaredTables)
                .isEqualTo(actualTables);
        assertThat(declaredEnable)
                .as("头注释 RLS 开启数（%d）与文件实计 ENABLE 数不一致", declaredEnable)
                .isEqualTo(actualEnable);
        assertThat(declaredPolicy)
                .as("头注释 RLS 策略数（%d）与文件实计 CREATE POLICY 数不一致", declaredPolicy)
                .isEqualTo(actualPolicy);
        assertThat(actualForce)
                .as("FORCE RLS 表数必须与 ENABLE 表数一致；缺 FORCE 的表在 FORCE 语境下会静默越权")
                .isEqualTo(actualEnable);
        assertThat(actualEnable)
                .as("RLS 开启表数不得为 0——若为 0 说明快照被替换成空壳，守卫必须红")
                .isPositive();
    }

    @Test
    @DisplayName("✅ 反向自检：更正说明必须仍在，避免头注释被整段回退到旧值")
    void correctionNotePresent() {
        assertThat(PlatformDdl.text())
                .as("头注释里的 2026-11-15 更正说明被删除；旧值 42/50 可能已悄悄回潮")
                .contains("2026-11-15 更正（P0）");
    }
}
