package cn.studykid.rag.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Docling 适配器的守卫测试（V1.0.2 S2-1 / T-4 待确认期间）。
 *
 * <p>这些断言锁住的<b>不是</b>远端调用细节（那待 T-4 确认），
 * 而是三条在 T-4 确认前后都<b>不得改变</b>的语义：
 * <ol>
 *   <li>未配置端点时 fail-closed（不返回空文档）；</li>
 *   <li>已配置但调用未实现时仍然 fail-closed（<b>绝不</b>回落到占位解析器）；</li>
 *   <li>空输入被拒（空内容解析出 0 片段会伪装成「录入成功」）。</li>
 * </ol>
 */
class DoclingDocumentParserPortTest {

    private static final byte[] SOME_PDF = "%PDF-1.7\n%x\n".getBytes(
            java.nio.charset.StandardCharsets.US_ASCII);

    @Test
    @DisplayName("未配置 base-url → isConfigured=false")
    void notConfiguredWhenBlank() {
        assertFalse(new DoclingDocumentParserPort("", 600).isConfigured());
        assertFalse(new DoclingDocumentParserPort("   ", 600).isConfigured());
        assertFalse(new DoclingDocumentParserPort(null, 600).isConfigured());
    }

    @Test
    @DisplayName("未配置时 parse 抛 DoclingUnavailableException（不返回空文档）")
    void unconfiguredFailsClosed() {
        DoclingDocumentParserPort port = new DoclingDocumentParserPort("", 600);
        DoclingDocumentParserPort.DoclingUnavailableException ex = assertThrows(
                DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> port.parse("doc-1", SOME_PDF));
        assertTrue(ex.getMessage().contains("未配置"));
    }

    @Test
    @DisplayName("已配置但服务不可达 → fail-closed 抛异常，绝不回落到占位解析器")
    void configuredButUnreachableFailsClosed() {
        // ⚠️ T-4 已实现后，本测试的语义从「调用未实现」变为「服务不可达」：
        //    指向一个不存在的地址，验证失败仍然显式抛出、不回落到占位解析器。
        //    这是最重要的一条——静默回落会以「看起来成功」的形态产出可能泄题的语料。
        DoclingDocumentParserPort port =
                new DoclingDocumentParserPort("http://127.0.0.1:1", 2);
        assertTrue(port.isConfigured());

        DoclingDocumentParserPort.DoclingUnavailableException ex = assertThrows(
                DoclingDocumentParserPort.DoclingUnavailableException.class,
                () -> port.parse("doc-1", SOME_PDF),
                "服务不可达时必须显式失败，不得回落到占位解析器");
        assertTrue(ex.getMessage().contains("调用 Docling 失败"),
                "应明确指出是调用失败：" + ex.getMessage());
    }

    @Test
    @DisplayName("空内容被拒（不得解析出空文档）")
    void emptyContentRejected() {
        DoclingDocumentParserPort port =
                new DoclingDocumentParserPort("http://docling.local:5001", 600);
        assertThrows(IllegalArgumentException.class, () -> port.parse("doc-1", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> port.parse("doc-1", null));
    }

    @Test
    @DisplayName("解析器版本标识非空且不再是待确认占位（T-4 已落地）")
    void parserVersionIsSet() {
        String v = DoclingDocumentParserPort.PARSER_VERSION;
        assertTrue(v != null && !v.isBlank(),
                "parser_version 是「这批语料用哪版解析器切的」的唯一依据，不能为空");
        assertTrue(!v.contains("pending"),
                "T-4 已确认，版本号不应再是 pending 占位：" + v);
    }
}
