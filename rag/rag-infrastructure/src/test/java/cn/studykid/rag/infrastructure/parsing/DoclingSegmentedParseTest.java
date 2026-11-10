package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.ports.BlockType;
import cn.studykid.rag.ports.LayoutBlock;

/**
 * 分段解析的单元测试（V1.0.2 S2-1 / 实测 148 页教材 710 秒 → 分段）。
 *
 * <p>这些测试不需要 Docling 服务，验证的是<b>合并与区间连续化</b>的纯逻辑——
 * 那是分段方案最容易出错、也最难在集成环境里发现的部分。
 */
class DoclingSegmentedParseTest {

    @Test
    @DisplayName("字符区间在各段合并后必须连续（否则「可回取原文」失效）")
    void charOffsetsAreContinuousAfterMerge() throws Exception {
        // 模拟两段独立映射的结果：每段都从 0 开始
        List<LayoutBlock> seg1 = List.of(
                block("A", 1, 0, 5),
                block("B", 1, 5, 12));
        List<LayoutBlock> seg2 = List.of(
                block("C", 21, 0, 8),
                block("D", 21, 8, 20));

        java.util.ArrayList<LayoutBlock> merged = new java.util.ArrayList<>(seg1);
        merged.addAll(seg2);

        var port = new DoclingDocumentParserPort("http://x", 10, 20, 60);
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method cand : DoclingDocumentParserPort.class.getDeclaredMethods()) {
            if (cand.getName().equals("rebaseCharOffsets")) {
                m = cand;
                break;
            }
        }
        assertTrue(m != null, "应存在 rebaseCharOffsets");
        m.setAccessible(true);
        List<LayoutBlock> out;
        try {
            @SuppressWarnings("unchecked")
            List<LayoutBlock> r = (List<LayoutBlock>) m.invoke(port, merged);
            out = r;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("rebaseCharOffsets 调用失败", e.getCause());
        }

        assertEquals(4, out.size());
        assertEquals(0, out.get(0).charStart());
        for (int i = 1; i < out.size(); i++) {
            assertEquals(out.get(i - 1).charEnd(), out.get(i).charStart(),
                    "块 " + i + " 的起点必须等于前一块的终点");
        }
        // 总长度 = 各段长度之和（5-0 + 12-5 + 8-0 + 20-8 = 32）
        assertEquals(32, out.get(3).charEnd());
        // 页码等其它字段必须原样保留
        assertEquals(21, out.get(2).pageNumber());
        assertEquals("C", out.get(2).text());
    }

    @Test
    @DisplayName("默认分段参数：20 页/段、60 段上限")
    void defaultSegmentParams() {
        assertEquals(20, DoclingDocumentParserPort.DEFAULT_PAGES_PER_SEGMENT);
        assertEquals(60, DoclingDocumentParserPort.DEFAULT_MAX_SEGMENTS);
    }

    // ------------------------------------------------------------------
    // 2026-11-10 静默失真守卫：对象流页数探测 + 封面截断 fail-closed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("页数藏在压缩对象流(/ObjStm)时仍能被探测到——旧实现返回 0 导致只切封面")
    void pageCountInCompressedObjectStreamIsDetected() throws Exception {
        // 构造一个最小 PDF：明文无 /Type /Page，但一个 FlateDecode 流里含 146 个 /Type /Page
        byte[] pdf = pdfWithCompressedPageObjects(146);
        java.lang.reflect.Method probe = probeMethod();
        int pages = (Integer) probe.invoke(port(), "doc-objstm", pdf);
        assertEquals(146, pages,
                "旧实现只扫明文会返回 0 → callDocling(1,0) → page_range=[1,1] 只解析封面；"
                        + "必须解压对象流拿到真实页数，才能走分段");
    }

    @Test
    @DisplayName("明文页树 /Count 仍优先（未压缩 PDF 行为不变）")
    void plainPageTreeCountStillWorks() throws Exception {
        String plain = "%PDF-1.7\n1 0 obj\n<</Type/Pages/Count 42/Kids[]>>\nendobj\n";
        java.lang.reflect.Method probe = probeMethod();
        int pages = (Integer) probe.invoke(port(), "doc-plain",
                plain.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(42, pages);
    }

    @Test
    @DisplayName("页覆盖守卫：只覆盖封面(第1页/共146页)必须 fail-closed，不能标成功")
    void truncatedCoverOnlyParseFailsClosed() {
        List<LayoutBlock> coverOnly = new ArrayList<>();
        coverOnly.add(block("封面图", 1, 0, 3));
        coverOnly.add(block("书名", 1, 3, 9));
        DoclingDocumentParserPort.DoclingUnavailableException ex = assertThrows(
                DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> DoclingDocumentParserPort.assertPageCoverage("doc-1", 146, coverOnly));
        assertTrue(ex.getMessage().contains("覆盖不足"),
                "必须明确指出页覆盖不足：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("封面"),
                "应点明「封面」这一典型截断形态：" + ex.getMessage());
    }

    @Test
    @DisplayName("页覆盖守卫：正常覆盖全册(末页≈总页数)放行；小册不触发")
    void fullCoverageParsePasses() {
        List<LayoutBlock> full = new ArrayList<>();
        full.add(block("p1", 1, 0, 2));
        full.add(block("p146", 146, 2, 6));
        // 不抛即通过
        DoclingDocumentParserPort.assertPageCoverage("doc-1", 146, full);
        // 小册（页数 < 10）即使只 1 页也不触发守卫
        DoclingDocumentParserPort.assertPageCoverage("doc-2", 5,
                List.of(block("p1", 1, 0, 2)));
    }

    @Test
    @DisplayName("编排入口：页数未知时整册提交不得发送 page_range（旧实现退化为 [1,1] 只切封面）")
    void unknownPageCountMustNotSendPageRange() throws Exception {
        // ⚠️ 守卫从 parseWithRaw() 编排入口进，不直连 callDocling 私有方法：
        //    只有这样才能锁住「探测 0 页 → 整册提交 → 不带 page_range」这条完整链路。
        //    用本地 HTTP 桩捕获真实 multipart 报文；桩回 500 让调用以
        //    DoclingUnavailableException 结束（本测试只关心**发出去的请求报文**）。
        java.util.concurrent.atomic.AtomicReference<byte[]> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/file", exchange -> {
            captured.set(exchange.getRequestBody().readAllBytes());
            byte[] err = "{\"detail\":\"stub\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, err.length);
            exchange.getResponseBody().write(err);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DoclingDocumentParserPort port = new DoclingDocumentParserPort(base, 5, 20, 60);
            // 明文无页树、无压缩对象流 → probePageCount 返回 0（页数未知）
            byte[] pdf = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
            assertThrows(DoclingDocumentParserPort.DoclingUnavailableException.class,
                    () -> port.parseWithRaw("doc-unknown-pages", pdf));

            String body = new String(captured.get(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"files\""), "multipart 必须含文件字段");
            assertTrue(body.contains("name=\"to_formats\""), "multipart 必须含输出格式字段");
            assertFalse(body.contains("name=\"page_range\""),
                    "页数未知时绝不能发 page_range：旧实现把 [1,0] 钳成 [1,1] 只解析封面，"
                            + "146 页教材产出 3~4 个封面占位片段却被标「已就绪」。实际报文："
                            + body.substring(0, Math.min(400, body.length())));
        } finally {
            server.stop(0);
        }
    }

    /** 反射调用私有 probePageCount（与既有 rebaseCharOffsets 测试同范式）。 */
    private static java.lang.reflect.Method probeMethod() throws Exception {
        for (java.lang.reflect.Method cand
                : DoclingDocumentParserPort.class.getDeclaredMethods()) {
            if (cand.getName().equals("probePageCount")) {
                cand.setAccessible(true);
                return cand;
            }
        }
        throw new AssertionError("应存在 probePageCount");
    }

    private static DoclingDocumentParserPort port() {
        return new DoclingDocumentParserPort("http://x", 10, 20, 60);
    }

    /**
     * 造一个"页对象全在压缩对象流里"的最小 PDF：
     * 明文部分刻意不含 /Type /Page / /Count，把 N 个 /Type /Page 放进
     * FlateDecode（zlib）压缩的 stream…endstream 块。
     */
    private static byte[] pdfWithCompressedPageObjects(int pages) throws Exception {
        StringBuilder obj = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            obj.append("/Type /Page /Parent 1 0 R ").append(i).append(" 0 R\n");
        }
        byte[] plain = obj.toString().getBytes(StandardCharsets.ISO_8859_1);
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, false); // zlib 封装
        d.setInput(plain);
        d.finish();
        byte[] buf = new byte[plain.length * 2 + 128];
        int n = d.deflate(buf);
        d.end();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write("%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1));
        out.write("1 0 obj\n<</Type/ObjStm/N ".getBytes(StandardCharsets.ISO_8859_1));
        out.write(String.valueOf(pages).getBytes(StandardCharsets.ISO_8859_1));
        out.write("/Filter/FlateDecode/Length ".getBytes(StandardCharsets.ISO_8859_1));
        out.write(String.valueOf(n).getBytes(StandardCharsets.ISO_8859_1));
        out.write(">>\nstream\n".getBytes(StandardCharsets.ISO_8859_1));
        out.write(buf, 0, n);
        out.write("\nendstream\nendobj\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static LayoutBlock block(String text, int page, int start, int end) {
        return new LayoutBlock(BlockType.PARAGRAPH, text, page, 0,
                "Unit 1", null, false, start, end);
    }
}
