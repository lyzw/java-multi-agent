package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 真实教材（沪教版六年级下册，148 页）的映射回归测试。
 *
 * <p><b>⚠️ 本测试的价值在于「锁定实测发现过的缺陷不再复发」</b>——
 * 下面每条断言都对应一次真实踩坑：
 * <ol>
 *   <li>答案区被题干里的 "answers" 误触发 → 89% 的块被判答案区；</li>
 *   <li>页眉「上海教育出版社」被 Docling 标成 section_header → 222 次噪声进正文；</li>
 *   <li>level=6 的单元内小节参与路径推进 → 94% 的路径超长且无限累积；</li>
 *   <li>封面标题污染初始路径 → "英语 / 英语 / …"。</li>
 * </ol>
 *
 * <p>夹具由真实 Docling 响应裁剪而来（见 README §2.5 的实测命令）。
 */
class DoclingRealTextbookMappingTest {

    private final DoclingResponseMapper mapper = new DoclingResponseMapper(null);

    private String realResponse() throws Exception {
        Path p = Path.of("src/test/resources/docling/real-textbook-response.json");
        if (!Files.exists(p)) {
            // ⚠️ 夹具未提供时跳过（不伪造通过）
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "真实教材响应夹具未提供（见 deploy/docling/README.md §2.5 的生成命令）");
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("题干中的 answers 不得触发答案区（曾导致 89% 误判）")
    void answersInQuestionDoesNotTriggerAnswerZone() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        long az = blocks.stream().filter(LayoutBlock::inAnswerZone).count();
        double ratio = (double) az / Math.max(1, blocks.size());
        assertTrue(ratio < 0.20,
                "答案区占比 " + String.format("%.1f%%", ratio * 100)
                        + " 过高——大概率是把题干里的 'answers' 当成了答案区标题");
    }

    @Test
    @DisplayName("章节路径不得过长（曾 94% 超 60 字符）")
    void sectionPathIsBounded() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        long tooLong = blocks.stream()
                .filter(b -> b.sectionPath() != null && b.sectionPath().length() > 60)
                .count();
        assertEquals(0, tooLong, "章节路径必须受控（最多 3 层且每层是真实单元标题）");
    }

    @Test
    @DisplayName("章节路径不得含页眉噪声")
    void sectionPathHasNoPageHeader() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        long noisy = blocks.stream()
                .filter(b -> b.sectionPath() != null && b.sectionPath().contains("出版社"))
                .count();
        assertEquals(0, noisy, "页眉不得进入章节路径");
    }

    @Test
    @DisplayName("页眉被识别为 BOILERPLATE（不得作为 HEADING 进正文）")
    void pageHeaderBecomesBoilerplate() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        // 正文里的出版社名应是 BOILERPLATE；封面上的那一条允许是 HEADING
        long headerAsHeading = blocks.stream()
                .filter(b -> b.type() == BlockType.HEADING
                        && "上海教育出版社".equals(b.text().trim())
                        && b.pageNumber() > 1)
                .count();
        assertEquals(0, headerAsHeading, "正文页的页眉不得标为 HEADING");
    }

    @Test
    @DisplayName("九个字段结构性缺失必须为零")
    void noStructuralFieldLoss() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        for (LayoutBlock b : blocks) {
            assertTrue(b.type() != null);
            assertTrue(b.pageNumber() >= 1);
            assertTrue(b.headingLevel() >= 0);
            assertTrue(b.sectionPath() != null);
            assertTrue(b.charEnd() >= b.charStart());
            assertTrue(b.text() != null && !b.text().isBlank(),
                    "块文本不能为空：" + b.charStart());
        }
    }
}
