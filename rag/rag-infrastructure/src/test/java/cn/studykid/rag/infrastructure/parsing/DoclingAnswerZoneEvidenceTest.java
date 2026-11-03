package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * G1 ④ 答案区识别证据（RAG-D09 G1 通过条件 ④）。
 *
 * <p><b>⚠️ 三册实测教材均无答案附录</b>（学生用书，答案单独成册），
 * 因此这一条无法用真实教材统计准确率。本类用<b>构造样本</b>验证规则逻辑：
 * 每条都是「教学语言里的干扰项」与「真正的答案区」的对照，
 * 证明判定不是靠关键词碰巧命中。
 *
 * <p>门禁口径（RAG-D04 §6）：准确率 ≥95%（含过判），
 * 且 <b>漏判 = 0</b>（单点否决——漏判即泄题）。
 */
class DoclingAnswerZoneEvidenceTest {

    private final DoclingResponseMapper mapper = new DoclingResponseMapper(null);

    private String doc(int page, String... titleLevelText) {
        StringBuilder t = new StringBuilder();
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < titleLevelText.length; i += 2) {
            if (i > 0) {
                t.append(",");
                c.append(",");
            }
            t.append(String.format(
                    "{\"self_ref\":\"#/texts/%d\",\"label\":\"section_header\","
                            + "\"text\":\"%s\",\"level\":%s,\"prov\":[{\"page_no\":%d}]}",
                    i / 2, titleLevelText[i], titleLevelText[i + 1], page));
            c.append(String.format("{\"$ref\":\"#/texts/%d\"}", i / 2));
        }
        return "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":[" + t + "],\"body\":{\"children\":[" + c + "]}}}}";
    }

    @Test
    @DisplayName("干扰项：题干里的 answers 不得触发答案区（实测曾误判 89%）")
    void answersInQuestionIsNotAnswerZone() {
        List<LayoutBlock> bs = mapper.map(doc(1,
                "C5 Further thinking Answer the questions.", "6",
                "Underline the answers to the questions in Feifei's email.", "6",
                "Circle the right answers.", "6"));
        for (LayoutBlock b : bs) {
            assertFalse(b.inAnswerZone(),
                    "题干里的 answers 是教学语言，不是答案区：" + b.text());
        }
    }

    @Test
    @DisplayName("正例：'Answer Key' 标题开启答案区，且延续到其后内容")
    void answerKeyOpensZone() {
        List<LayoutBlock> bs = mapper.map(doc(20,
                "Unit 1 Hello", "1",
                "Answer Key", "3"));
        assertEquals("Unit 1 Hello", bs.get(0).sectionPath());
        assertTrue(bs.get(1).inAnswerZone(), "Answer Key 必须是答案区标题");
    }

    @Test
    @DisplayName("正例：中文「参考答案」同样触发")
    void chineseAnswerKey() {
        List<LayoutBlock> bs = mapper.map(doc(30, "参考答案", "2"));
        assertTrue(bs.get(0).inAnswerZone());
    }

    @Test
    @DisplayName("漏判反证：'Answers' 单词标题须命中（否则答案会被判成讲解 → 泄题）")
    void bareAnswersHeadingHits() {
        List<LayoutBlock> bs = mapper.map(doc(40, "Answers", "2"));
        assertTrue(bs.get(0).inAnswerZone(),
                "整块就是 'Answers' 的标题必须判答案区——漏判即泄题");
    }

    @Test
    @DisplayName("边界：正文段落里提到参考答案不触发（长度 > 40）")
    void longParagraphMentioningAnswersIsNotHeading() {
        List<LayoutBlock> bs = mapper.map(doc(50,
                "Students should check their answers with the reference sheet provided by the teacher.", "6"));
        assertFalse(bs.get(0).inAnswerZone(),
                "长段落不是标题，不能开启答案区");
    }

    @Test
    @DisplayName("退出：答案区之后遇到新标题即结束")
    void zoneEndsAtNextHeading() {
        String resp = "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":["
                + "{\"self_ref\":\"#/texts/0\",\"label\":\"section_header\",\"text\":\"Answer Key\","
                + "\"level\":2,\"prov\":[{\"page_no\":60}]},"
                + "{\"self_ref\":\"#/texts/1\",\"label\":\"section_header\",\"text\":\"Unit 2 School\","
                + "\"level\":1,\"prov\":[{\"page_no\":62}]},"
                + "{\"self_ref\":\"#/texts/2\",\"label\":\"text\",\"text\":\"New chapter body\","
                + "\"level\":null,\"prov\":[{\"page_no\":62}]}"
                + "],\"body\":{\"children\":[{\"$ref\":\"#/texts/0\"},{\"$ref\":\"#/texts/1\"},"
                + "{\"$ref\":\"#/texts/2\"}]}}}}";
        List<LayoutBlock> bs = mapper.map(resp);
        assertTrue(bs.get(0).inAnswerZone(), "答案区标题");
        assertFalse(bs.get(1).inAnswerZone(), "遇到新标题应退出答案区");
        assertFalse(bs.get(2).inAnswerZone(), "新章节正文不是答案区");
    }

    @Test
    @DisplayName("误导项：'Answer the questions' 不是答案区标题")
    void answerTheQuestionsIsNotKey() {
        List<LayoutBlock> bs = mapper.map(doc(70, "Answer the questions.", "3"));
        assertFalse(bs.get(0).inAnswerZone(),
                "「回答下列问题」是题干指令，不是答案区");
    }
}
