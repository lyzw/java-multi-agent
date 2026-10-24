package cn.studykid.multiagent.core.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 辅导业务词表测试（DEC-6 白名单 · fail-closed 归一）。
 *
 * <p><b>本类守住的不变量</b>：<b>不认识就当没有</b>。
 * {@code input_payload} 是客户端可控的 JSON，
 * 归一函数一旦「宽松」放行，客户端就能把任意文本伪装成受信任的分类字段
 * 送到前端渲染——那等于给 XSS 与钓鱼文案开了后门。
 */
@DisplayName("TutoringVocabulary · 学科/年级 fail-closed 归一")
class TutoringVocabularyTest {

    @Test
    @DisplayName("✅ 词表内取值原样返回")
    void knownValuesAreReturnedAsIs() {
        assertThat(TutoringVocabulary.subject("数学")).isEqualTo("数学");
        assertThat(TutoringVocabulary.subject("物理")).isEqualTo("物理");
        assertThat(TutoringVocabulary.grade("初二")).isEqualTo("初二");
    }

    @Test
    @DisplayName("✅ 首尾空白被裁掉后仍能命中（客户端常带空格）")
    void surroundingWhitespaceIsTrimmed() {
        assertThat(TutoringVocabulary.subject("  数学 ")).isEqualTo("数学");
        assertThat(TutoringVocabulary.grade("\t初二\n")).isEqualTo("初二");
    }

    @Test
    @DisplayName("⛔ 词表外取值一律 null——绝不原样回显客户端文本")
    void unknownValuesAreRejected() {
        // ⚠️ 这一条是整个 fail-closed 设计的核心断言：
        //    下面的字符串都是「客户端能塞进 input_payload 的任意文本」，
        //    它们一个都不该被回显。
        assertThat(TutoringVocabulary.subject("地理")).isNull();
        assertThat(TutoringVocabulary.subject("数学分析")).isNull();
        assertThat(TutoringVocabulary.subject("物理化")).isNull();
        assertThat(TutoringVocabulary.subject("<script>alert(1)</script>")).isNull();
        assertThat(TutoringVocabulary.subject("李明的数学")).isNull();
        assertThat(TutoringVocabulary.grade("高一")).isNull();
        assertThat(TutoringVocabulary.grade("初四")).isNull();
    }

    @Test
    @DisplayName("✅ null / 空串 / 纯空白 → null（不是空字符串）")
    void blankInputsYieldNull() {
        assertThat(TutoringVocabulary.subject(null)).isNull();
        assertThat(TutoringVocabulary.subject("")).isNull();
        assertThat(TutoringVocabulary.subject("   ")).isNull();
        assertThat(TutoringVocabulary.grade(null)).isNull();
        assertThat(TutoringVocabulary.grade("")).isNull();
    }

    @Test
    @DisplayName("✅ 学科与年级互不串味（数学 不是 年级，初二 不是 学科）")
    void subjectsAndGradesDoNotCrossOver() {
        assertThat(TutoringVocabulary.subject("初二")).isNull();
        assertThat(TutoringVocabulary.grade("数学")).isNull();
    }

    @Test
    @DisplayName("✅ isKnownSubject 与 subject 判定一致")
    void isKnownSubjectMatchesNormalizer() {
        assertThat(TutoringVocabulary.isKnownSubject("数学")).isTrue();
        assertThat(TutoringVocabulary.isKnownSubject("地理")).isFalse();
        assertThat(TutoringVocabulary.isKnownSubject(null)).isFalse();
    }

    @Test
    @DisplayName("✅ 词表非空且互不重复（前端下拉直接用）")
    void vocabularyIsUsableAsDropdownOptions() {
        assertThat(TutoringVocabulary.SUBJECTS).isNotEmpty();
        assertThat(TutoringVocabulary.GRADES).isNotEmpty();
        assertThat(TutoringVocabulary.SUBJECTS).doesNotHaveDuplicates();
        assertThat(TutoringVocabulary.GRADES).doesNotHaveDuplicates();
        assertThat(TutoringVocabulary.subjectSet())
                .containsExactlyInAnyOrderElementsOf(TutoringVocabulary.SUBJECTS);
    }
}
