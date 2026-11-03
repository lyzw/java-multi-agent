package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Docling 响应映射测试（V1.0.2 T-4 / S2-1）。
 *
 * <p><b>⚠️ 夹具是 2026-11-02 对 docling-serve 的**真实响应**裁剪而来</b>
 * （{@code src/test/resources/docling/sample-response.json}），
 * 不是手写假设。这保证「映射规则与真实服务输出一致」这件事被测试锁定——
 * Docling 升级改变字段时，这组测试会失败而不是静默产出错块。
 */
class DoclingResponseMapperTest {

    private final DoclingResponseMapper mapper = new DoclingResponseMapper(null);

    private String realResponse() throws Exception {
        return Files.readString(
                Path.of("src/test/resources/docling/sample-response.json"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("真实响应 → LayoutBlock[]，九字段齐全")
    void mapsRealResponse() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        assertFalse(blocks.isEmpty(), "真实响应应产出块");

        for (LayoutBlock b : blocks) {
            assertNotNull(b.type());
            assertTrue(b.pageNumber() >= 1, "pageNumber 必须 >= 1");
            assertNotNull(b.sectionPath());
            assertFalse(b.text().isBlank());
            assertTrue(b.charEnd() >= b.charStart(), "字符区间必须单调");
        }
        // 字段完整性断言（对应方案要求的九字段）
        LayoutBlock first = blocks.get(0);
        assertEquals(BlockType.HEADING, first.type());
        assertEquals("Unit 1 Hello", first.text());
        assertEquals(1, first.headingLevel());
        assertEquals(1, first.pageNumber());
        assertEquals("Unit 1 Hello", first.sectionPath());
    }

    @Test
    @DisplayName("阅读顺序以 body.children 为准，且字符区间连续")
    void readingOrderAndOffsets() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());
        // 首块从 0 开始，后续首尾相接（无空洞、无重叠）
        assertEquals(0, blocks.get(0).charStart());
        for (int i = 1; i < blocks.size(); i++) {
            assertEquals(blocks.get(i - 1).charEnd(), blocks.get(i).charStart(),
                    "块 " + i + " 的起始必须等于前一块的结束");
        }
    }

    @Test
    @DisplayName("答案区必须是跨块状态：Answer Key 之后的答案内容同样判 YES")
    void answerZoneIsStateful() throws Exception {
        List<LayoutBlock> blocks = mapper.map(realResponse());

        int keyIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).text().contains("Answer Key")) {
                keyIdx = i;
                break;
            }
        }
        assertTrue(keyIdx >= 0, "夹具应包含 Answer Key");
        assertTrue(blocks.get(keyIdx).inAnswerZone(), "Answer Key 标题本身必须是答案区");

        // ⚠️ 核心断言：标题**之后的**答案内容必须也是答案区。
        //    只判标题不判内容 = 答案以讲解身份进入检索 = 直接泄题。
        for (int i = keyIdx + 1; i < blocks.size(); i++) {
            assertTrue(blocks.get(i).inAnswerZone(),
                    "Answer Key 之后的块必须延续答案区：" + blocks.get(i).text());
        }
    }

    @Test
    @DisplayName("新的顶层标题会退出答案区（否则下一章会被整章误判）")
    void newTopLevelHeadingExitsAnswerZone() {
        String resp = "{\"status\":\"success\",\"document\":{\"json_content\":{"
                + "\"texts\":["
                + "{\"self_ref\":\"#/texts/0\",\"label\":\"section_header\",\"text\":\"Answer Key\",\"level\":1,\"prov\":[{\"page_no\":1}]},"
                + "{\"self_ref\":\"#/texts/1\",\"label\":\"text\",\"text\":\"1. My name is Li Lei.\",\"prov\":[{\"page_no\":1}]},"
                + "{\"self_ref\":\"#/texts/2\",\"label\":\"section_header\",\"text\":\"Unit 2 School\",\"level\":1,\"prov\":[{\"page_no\":2}]},"
                + "{\"self_ref\":\"#/texts/3\",\"label\":\"text\",\"text\":\"New chapter body.\",\"prov\":[{\"page_no\":2}]}"
                + "],\"body\":{\"children\":[{\"$ref\":\"#/texts/0\"},{\"$ref\":\"#/texts/1\"},{"
                + "\"$ref\":\"#/texts/2\"},{\"$ref\":\"#/texts/3\"}]}}}}";
        List<LayoutBlock> blocks = mapper.map(resp);
        assertTrue(blocks.get(1).inAnswerZone(), "答案区内容延续");
        assertFalse(blocks.get(2).inAnswerZone(), "新的顶层标题应退出答案区");
        assertFalse(blocks.get(3).inAnswerZone(), "新章节正文不应是答案区");
    }

    @Test
    @DisplayName("题号抽取")
    void extractsExerciseNo() {
        assertEquals("1", DoclingResponseMapper.extractExerciseNo("1. What is your name?"));
        assertEquals("2", DoclingResponseMapper.extractExerciseNo("(2) How old"));
        assertEquals("3", DoclingResponseMapper.extractExerciseNo("Exercise 3 Read and answer"));
        assertNull(DoclingResponseMapper.extractExerciseNo("This is a plain paragraph"));
    }

    @Test
    @DisplayName("label 映射")
    void mapsLabels() {
        assertEquals(BlockType.HEADING, DoclingResponseMapper.mapLabel("section_header"));
        assertEquals(BlockType.HEADING, DoclingResponseMapper.mapLabel("title"));
        assertEquals(BlockType.LIST_ITEM, DoclingResponseMapper.mapLabel("list_item"));
        assertEquals(BlockType.TABLE, DoclingResponseMapper.mapLabel("table"));
        assertEquals(BlockType.FIGURE, DoclingResponseMapper.mapLabel("picture"));
        assertEquals(BlockType.BOILERPLATE, DoclingResponseMapper.mapLabel("page_header"));
        assertEquals(BlockType.BOILERPLATE, DoclingResponseMapper.mapLabel("page_footer"));
        // ⚠️ 未知 label 当段落而非丢弃：丢弃会让正文莫名变少
        assertEquals(BlockType.PARAGRAPH, DoclingResponseMapper.mapLabel("unknown_thing"));
    }

    @Test
    @DisplayName("json_content 是 JSON 字符串时也能解析（不同版本行为差异）")
    void handlesStringJsonContent() throws Exception {
        String asString = "{\"status\":\"success\",\"document\":{\"json_content\":\""
                + "{\\\"texts\\\":[{\\\"self_ref\\\":\\\"#/texts/0\\\",\\\"label\\\":\\\"text\\\","
                + "\\\"text\\\":\\\"hello\\\",\\\"prov\\\":[{\\\"page_no\\\":1}]}],"
                + "\\\"body\\\":{\\\"children\\\":[{\\\"$ref\\\":\\\"#/texts/0\\\"}]}}\"}}";
        List<LayoutBlock> blocks = mapper.map(asString);
        assertEquals(1, blocks.size());
        assertEquals("hello", blocks.get(0).text());
    }

    @Test
    @DisplayName("status=failure → 抛异常而非返回空块")
    void failureStatusThrows() {
        String resp = "{\"status\":\"failure\",\"errors\":[{\"error_message\":\"bad pdf\"}]}";
        assertThrows(DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> mapper.map(resp));
    }

    @Test
    @DisplayName("缺 json_content → 抛异常（提示 to_formats 可能漏了 json）")
    void missingJsonContentThrows() {
        String resp = "{\"status\":\"success\",\"document\":{}}";
        DoclingDocumentParserPort.DoclingUnavailableException ex = assertThrows(
                DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> mapper.map(resp));
        assertTrue(ex.getMessage().contains("to_formats"));
    }

    @Test
    @DisplayName("产出 0 块 → 抛异常（不把空文档当成功）")
    void emptyBlocksThrows() {
        String resp = "{\"status\":\"success\",\"document\":{\"json_content\":"
                + "{\"texts\":[],\"body\":{\"children\":[]}}}}";
        assertThrows(DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> mapper.map(resp));
    }

    @Test
    @DisplayName("非法 JSON → 抛异常")
    void invalidJsonThrows() {
        assertThrows(DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> mapper.map("not-json"));
    }
}
