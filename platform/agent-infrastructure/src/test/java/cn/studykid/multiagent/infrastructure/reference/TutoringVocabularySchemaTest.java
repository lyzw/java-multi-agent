package cn.studykid.multiagent.infrastructure.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.reference.TutoringVocabulary;

/**
 * 词表 ↔ DB 约束漂移守卫。
 *
 * <p><b>为什么必须有这条测试</b>：学科/年级在系统里有<b>两份</b>必须永远一致的表述——
 * <ul>
 *   <li>{@code TutoringVocabulary}（Java 词表）：前端下拉的取值来源，
 *       以及 Run 列表回填学科时的 fail-closed 校验依据；</li>
 *   <li>{@code V2__student_profile_and_weakness.sql} 的 CHECK 约束：
 *       画像表的<b>写入</b>边界。</li>
 * </ul>
 * 两份表述一旦漂移，后果是<b>静默</b>的：Java 词表多了一个「地理」，
 * 用户能在下拉里选到、也能提交成功，但画像表写入会被 DB 拒掉——
 * 错误发生在离病因很远的地方。
 *
 * <p>所以把它变成<b>构建期硬门禁</b>：漂移即测试红。
 */
@DisplayName("辅导词表 · 与 V2 CHECK 约束一致性")
class TutoringVocabularySchemaTest {

    /** 迁移脚本路径（模块内相对路径）。 */
    private static final Path V2 = Path.of(
            "src/main/resources/db/migration/V2__student_profile_and_weakness.sql");

    /** `CONSTRAINT <名字>` 后紧跟的 `CHECK (<列> IN ('a','b'))`。 */
    private static final Pattern CHECK_IN = Pattern.compile(
            "CONSTRAINT\\s+(\\w+_chk)\\s+CHECK\\s*\\(\\s*(\\w+)\\s+IN\\s*\\(([^)]*)\\)\\)",
            Pattern.DOTALL);

    @Test
    @DisplayName("✅ 学科词表与 student_profile_subject_chk 完全一致")
    void subjectVocabularyMatchesSchemaConstraint() throws IOException {
        assertThat(valuesOfConstraint("student_profile_subject_chk"))
                .as("⛔ Java 词表与 DB CHECK 约束漂移：前端能选到、却写不进画像表")
                .containsExactlyInAnyOrderElementsOf(TutoringVocabulary.SUBJECTS);
    }

    @Test
    @DisplayName("✅ 年级词表与 student_profile_grade_chk 完全一致")
    void gradeVocabularyMatchesSchemaConstraint() throws IOException {
        assertThat(valuesOfConstraint("student_profile_grade_chk"))
                .as("⛔ Java 词表与 DB CHECK 约束漂移：年级会出现「选得到、写不进」")
                .containsExactlyInAnyOrderElementsOf(TutoringVocabulary.GRADES);
    }

    /**
     * 取出指定 CHECK 约束的 IN 列表取值。
     *
     * <p>⚠️ <b>刻意解析 SQL 文本而不是连库</b>：这条守卫要能在<b>没有数据库</b>的
     * 环境里也跑（CI 的单元测试阶段）。连库的话，缺库时会「跳过」——
     * 而漂移恰恰最可能在没人跑 IT 的时候发生。
     */
    private static List<String> valuesOfConstraint(String constraintName) throws IOException {
        String sql = Files.readString(V2, StandardCharsets.UTF_8);
        Matcher m = CHECK_IN.matcher(sql);
        while (m.find()) {
            if (!m.group(1).equals(constraintName)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            Matcher q = Pattern.compile("'([^']*)'").matcher(m.group(3));
            while (q.find()) {
                values.add(q.group(1));
            }
            return values;
        }
        throw new IllegalStateException(
                "未在 " + V2 + " 中找到约束 " + constraintName + "（迁移脚本结构变了？）");
    }
}
