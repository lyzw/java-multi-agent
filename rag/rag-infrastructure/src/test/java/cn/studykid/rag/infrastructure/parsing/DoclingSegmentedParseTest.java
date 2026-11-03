package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    private static LayoutBlock block(String text, int page, int start, int end) {
        return new LayoutBlock(cn.studykid.rag.ports.BlockType.PARAGRAPH, text, page, 0,
                "Unit 1", null, false, start, end);
    }
}
