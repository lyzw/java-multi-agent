package cn.studykid.rag.infrastructure.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.management.ports.ObjectStorePort;

/**
 * P1-3：IR 落盘适配器测试（三件套 / 键隔离 / 路径穿越 / fail-closed）。
 */
class ObjectStorePremiseArtifactSinkTest {

    /** 内存对象存储桩（记录键→字节，可注入失败）。 */
    static class MemStore implements ObjectStorePort {
        final Map<String, byte[]> data = new HashMap<>();
        boolean fail;

        @Override
        public String store(String logicalKey, byte[] content) {
            if (fail) {
                throw new IllegalStateException("落盘失败（模拟）");
            }
            data.put(logicalKey, content);
            return logicalKey;
        }

        @Override
        public String sha256(byte[] content) {
            return "fake";
        }

        @Override
        public byte[] read(String objectRef) {
            byte[] v = data.get(objectRef);
            if (v == null) {
                throw new IllegalStateException("对象不存在");
            }
            return v;
        }

        @Override
        public boolean isAvailable() {
            return !fail;
        }
    }

    @Test
    @DisplayName("三件套落盘：Markdown/JSON/原页缓存各落一个相对键，内容一致")
    void storesThreeArtifacts() {
        MemStore store = new MemStore();
        var sink = new ObjectStorePremiseArtifactSink(store);
        String mdRef = sink.storeMarkdown("doc-1", "# 光的直线传播\n");
        String jsonRef = sink.storeJson("doc-1", "{\"blocks\":[]}");
        String cacheRef = sink.storePageCache("doc-1", new byte[]{1, 2, 3});

        assertEquals("premise/doc-1/markdown.md", mdRef);
        assertEquals("premise/doc-1/ir.json", jsonRef);
        assertEquals("premise/doc-1/page-cache.bin", cacheRef);
        assertEquals("# 光的直线传播\n", new String(store.data.get(mdRef), StandardCharsets.UTF_8));
        assertEquals(3, store.data.get(cacheRef).length);
        assertEquals(3, store.data.size(), "三个产物落三个键，不互相覆盖");
    }

    @Test
    @DisplayName("路径穿越：documentId 含 / 或 .. 必须拒绝，不落盘")
    void rejectsPathTraversal() {
        var sink = new ObjectStorePremiseArtifactSink(new MemStore());
        assertThrows(IllegalArgumentException.class,
                () -> sink.storeMarkdown("../../etc/passwd", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> sink.storeJson("a/b", "{}"));
    }

    @Test
    @DisplayName("fail-closed：底层落盘失败必须抛异常，不返回空引用")
    void failClosedOnStoreError() {
        MemStore store = new MemStore();
        store.fail = true;
        var sink = new ObjectStorePremiseArtifactSink(store);
        assertThrows(IllegalStateException.class, () -> sink.storeMarkdown("doc-1", "x"));
        assertFalse(sink.isAvailable(), "落盘失败时通道不可用");
    }

    @Test
    @DisplayName("空内容拒绝：null/空字节不得冒充落盘成功")
    void rejectsEmptyContent() {
        var sink = new ObjectStorePremiseArtifactSink(new MemStore());
        assertThrows(IllegalArgumentException.class, () -> sink.storeJson("doc-1", null));
        assertThrows(IllegalArgumentException.class,
                () -> sink.storePageCache("doc-1", new byte[0]));
        assertTrue(new ObjectStorePremiseArtifactSink(new MemStore()).isAvailable());
    }
}
