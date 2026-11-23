package cn.studykid.rag.premise.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.PremiseArtifactSink;

/**
 * P1-3：IR 落盘门面闭环测试——渲染→落盘→回填引用→回取原文，含 fail-closed 反证。
 */
class PremiseArtifactWriterTest {

    static class MemSink implements PremiseArtifactSink {
        final java.util.Map<String, String> text = new java.util.HashMap<>();
        final java.util.Map<String, byte[]> bytes = new java.util.HashMap<>();
        boolean available = true;
        boolean fail;

        private String key(String documentId, String kind) {
            return documentId + "/" + kind;
        }

        @Override
        public String storeMarkdown(String documentId, String markdown) {
            if (fail) throw new IllegalStateException("落盘失败");
            text.put(key(documentId, "md"), markdown);
            return key(documentId, "md");
        }

        @Override
        public String storeJson(String documentId, String json) {
            if (fail) throw new IllegalStateException("落盘失败");
            text.put(key(documentId, "json"), json);
            return key(documentId, "json");
        }

        @Override
        public String storePageCache(String documentId, byte[] original) {
            if (fail) throw new IllegalStateException("落盘失败");
            bytes.put(key(documentId, "cache"), original);
            return key(documentId, "cache");
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }

    private static PremiseDocument doc() {
        String text = "光在同种均匀介质中沿直线传播。";
        LayoutBlock layout = new LayoutBlock(BlockType.PARAGRAPH, text, 1, 0,
                "第1章", null, false, 0, text.length());
        PremiseBlock block = new PremiseBlock(layout,
                new double[]{0.1, 0.2, 0.3, 0.4}, 0.95);
        return new PremiseDocument("doc-1", List.of(block),
                new ParseTrace("mineru", "4.1.7", 1, false, 1, List.of()),
                "raw-ref");
    }

    @Test
    @DisplayName("闭环：渲染落盘 → 回填引用 → 逐字段带过 doclingJsonRef → 回取原文命中")
    void endToEnd() {
        MemSink sink = new MemSink();
        var writer = new PremiseArtifactWriter(sink);
        PremiseDocument attached = writer.writeAndAttach(doc(), new byte[]{1, 2, 3});

        assertEquals("doc-1/md", attached.markdownRef());
        assertEquals("doc-1/cache", attached.pageCacheRef());
        assertEquals("raw-ref", attached.doclingJsonRef(), "compat 不得清空 doclingJsonRef");
        assertNotNull(sink.text.get("doc-1/json"), "JSON 中间产物已落盘");
        assertTrue(sink.text.get("doc-1/md").contains("光在同种均匀介质中沿直线传播"));
        assertEquals(3, sink.bytes.get("doc-1/cache").length);

        // 回取原文：从渲染后的 IR 按 char 区间切出，命中原文
        IntermediateRepresentation ir = writer.renderer().render(attached);
        assertEquals(List.of("光在同种均匀介质中沿直线传播。"), writer.renderer().retrieveOriginal(ir));
    }

    @Test
    @DisplayName("反证：落盘通道不可用必须 fail-closed，不返回无引用文档")
    void failClosedWhenUnavailable() {
        MemSink sink = new MemSink();
        sink.available = false;
        assertThrows(IllegalStateException.class,
                () -> new PremiseArtifactWriter(sink).writeAndAttach(doc(), new byte[]{1}));
    }

    @Test
    @DisplayName("反证：底层落盘抛错必须穿透，不静默产出半成品")
    void failClosedOnStoreError() {
        MemSink sink = new MemSink();
        sink.fail = true;
        assertThrows(IllegalStateException.class,
                () -> new PremiseArtifactWriter(sink).writeAndAttach(doc(), new byte[]{1}));
    }
}
