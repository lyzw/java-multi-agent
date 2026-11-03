package cn.studykid.rag.core.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 批量导入包解析的测试（V1.0.2 S3-5）。
 *
 * <p>重点覆盖三类风险：<b>路径穿越</b>、<b>清单与 ZIP 不对齐</b>、<b>逐行独立成败</b>。
 */
class BulkImportRulesTest {

    /** 构造一个 ZIP。 */
    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Nested
    @DisplayName("CSV 解析")
    class Csv {

        @Test
        @DisplayName("基本表头与行")
        void basic() {
            List<List<String>> t = BulkImportRules.parseCsv("a,b\n1,2\n3,4");
            assertEquals(3, t.size());
            assertEquals(List.of("1", "2"), t.get(1));
        }

        @Test
        @DisplayName("引号包裹含逗号与转义双引号")
        void quoted() {
            List<List<String>> t = BulkImportRules.parseCsv(
                    "filename,title\n\"a,b.pdf\",\"说\"\"引号\"\"的书\"");
            assertEquals("a,b.pdf", t.get(1).get(0));
            assertEquals("说\"引号\"的书", t.get(1).get(1));
        }

        @Test
        @DisplayName("兼容 CRLF 与 CR")
        void crlf() {
            List<List<String>> t = BulkImportRules.parseCsv("a,b\r\n1,2\r3,4");
            assertEquals(3, t.size());
        }
    }

    @Nested
    @DisplayName("ZIP + 清单解析")
    class Parse {

        @Test
        @DisplayName("正常包：清单行与文件都解析出来")
        void happyPath() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("manifest.csv", "filename,title,isbn\nbook1.pdf,数学第一册,978-7-000-00001-1\n");
            entries.put("book1.pdf", "%PDF-1.7");
            BulkImportRules.ParsedBatch b = BulkImportRules.parse(zip(entries));

            assertEquals(1, b.rows().size());
            assertEquals("book1.pdf", b.rows().get(0).filename());
            assertEquals("数学第一册", b.rows().get(0).title());
            assertEquals("978-7-000-00001-1", b.rows().get(0).fields().get("isbn"));
            assertTrue(b.files().containsKey("book1.pdf"));
            assertEquals(0, b.errors().size());
            assertEquals(0, b.extras().size());
        }

        @Test
        @DisplayName("缺清单文件 → 整批拒绝")
        void missingManifest() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("a.pdf", "%PDF");
            assertThrows(IllegalArgumentException.class, () -> BulkImportRules.parse(zip(entries)));
        }

        @Test
        @DisplayName("缺必填列 → 记录错误")
        void missingColumn() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("manifest.csv", "filename\nbook1.pdf\n");
            entries.put("book1.pdf", "%PDF");
            BulkImportRules.ParsedBatch b = BulkImportRules.parse(zip(entries));
            assertTrue(b.errors().stream().anyMatch(e -> e.reason().contains("title")));
        }

        @Test
        @DisplayName("逐行独立成败：一行缺 title 不影响其余行")
        void perRowIndependence() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("manifest.csv", "filename,title\nok.pdf,好的一册\n,\nbad.pdf,\n");
            entries.put("ok.pdf", "%PDF");
            entries.put("bad.pdf", "%PDF");
            BulkImportRules.ParsedBatch b = BulkImportRules.parse(zip(entries));

            assertEquals(1, b.rows().size(), "只有 ok.pdf 应成功");
            assertEquals(2, b.errors().size(), "两行有问题的应各自记错");
        }

        @Test
        @DisplayName("清单未列的文件 → 计入 extras 而非静默入库")
        void extrasIgnored() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("manifest.csv", "filename,title\nbook1.pdf,书一\n");
            entries.put("book1.pdf", "%PDF");
            entries.put("stray.pdf", "%PDF");
            BulkImportRules.ParsedBatch b = BulkImportRules.parse(zip(entries));

            assertEquals(List.of("stray.pdf"), b.extras());
            assertEquals(1, b.rows().size());
        }

        @Test
        @DisplayName("路径穿越条目 → 整批拒绝")
        void rejectsPathTraversal() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("manifest.csv", "filename,title\nbook1.pdf,书\n");
            entries.put("../evil.pdf", "%PDF");
            assertThrows(IllegalArgumentException.class, () -> BulkImportRules.parse(zip(entries)));
        }

        @Test
        @DisplayName("空包被拒")
        void emptyRejected() {
            assertThrows(IllegalArgumentException.class, () -> BulkImportRules.parse(new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> BulkImportRules.parse(null));
        }

        @Test
        @DisplayName("子目录内的清单与文件按文件名匹配")
        void subDirectory() throws Exception {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("batch/manifest.csv", "filename,title\nbook1.pdf,书一\n");
            entries.put("batch/book1.pdf", "%PDF");
            BulkImportRules.ParsedBatch b = BulkImportRules.parse(zip(entries));
            assertEquals(1, b.rows().size());
            assertTrue(b.files().containsKey("book1.pdf"), "子目录应被规范化");
            assertEquals(0, b.extras().size());
        }
    }

    @Nested
    @DisplayName("默认值（T-3 建议默认）")
    class Defaults {

        @Test
        @DisplayName("单批上限与既有 sources/batch 的 max=100 对齐")
        void batchLimitMatchesExisting() {
            assertEquals(100, BulkImportRules.MAX_BATCH_FILES);
        }

        @Test
        @DisplayName("ZIP 上限 2GB")
        void zipLimit() {
            assertEquals(2L * 1024 * 1024 * 1024, BulkImportRules.MAX_ZIP_BYTES);
            assertFalse(BulkImportRules.MAX_SINGLE_FILE_BYTES > BulkImportRules.MAX_ZIP_BYTES);
        }
    }
}
