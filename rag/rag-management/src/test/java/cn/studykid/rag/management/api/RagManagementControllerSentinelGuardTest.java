package cn.studykid.rag.management.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.rag.management.ports.CorpusIndexingPort;
import cn.studykid.rag.management.ports.CorpusManagementPort;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.ObjectStorePort;

/**
 * V1.0.2 S1-3 / S1-4 的守卫测试：哨兵回落必须已删除、魔数校验必须生效。
 *
 * <p><b>⚠️ 这些测试存在的理由是「错误必须可被回归捕获」</b>：
 * 哨兵 UUID（{@code 00000000-…}）回落曾长期存在——无令牌主体时把全零 UUID
 * 当作匿名操作人写进审计，事后无法追溯「谁导入了这份语料」。
 * 一旦有人为了兼容旧调用把它加回来，本类第一条断言就会失败。
 */
class RagManagementControllerSentinelGuardTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SOURCE_ID = "22222222-2222-2222-2222-222222222222";
    private static final String REVIEWER = "33333333-3333-3333-3333-333333333333";
    private static final String TENANT_CALLER = cn.studykid.rag.core.security.RagCallerAttributes.DOMAIN_TENANT;

    private static final byte[] REAL_PDF = "%PDF-1.7\n%probe\n".getBytes(StandardCharsets.US_ASCII);

    private final CorpusManagementPort port = mock(CorpusManagementPort.class);
    private final CorpusIndexingPort indexingPort = mock(CorpusIndexingPort.class);
    private final DictionaryPort dictionaryPort = mock(DictionaryPort.class);
    private final ObjectStorePort objectStore = mock(ObjectStorePort.class);

    /** 4 参构造器（去重预检与 job 端口均未装配）。 */
    private RagManagementController controller() {
        return new RagManagementController(port, indexingPort, dictionaryPort, objectStore);
    }

    @Test
    @DisplayName("无令牌主体且无 registeredBy → 401，绝不回落哨兵 UUID")
    void noPrincipalIsUnauthorized() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "book.pdf", "application/pdf", REAL_PDF);
        when(objectStore.sha256(any())).thenReturn("a".repeat(64));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller().importDocument(TENANT, null, "TENANT", SOURCE_ID,
                        null, null, null, file, null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode(),
                "匿名录入是审计黑洞（方案 §5.1 硬规则 2），必须 401 而非写入全零 UUID");
    }

    @Test
    @DisplayName("registeredBy 可作为操作人兜底，不再被判为匿名")
    void registeredByStillWorks() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "book.pdf", "application/pdf", REAL_PDF);
        when(objectStore.sha256(any())).thenReturn("b".repeat(64));
        when(objectStore.store(any(), any())).thenReturn("book.pdf/uuid.pdf");
        when(port.importDocument(any(), any(), any(), any())).thenReturn(null);

        try {
            controller().importDocument(TENANT, null, "TENANT", SOURCE_ID,
                    null, null, REVIEWER, file, null, null);
        } catch (ResponseStatusException e) {
            // 走到「来源不存在」的 404 才是正确路径；401 说明主体仍被误判为匿名。
            assertTrue(e.getStatusCode() != HttpStatus.UNAUTHORIZED,
                    "registeredBy 已给出主体，不应被判为匿名");
            assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        }
    }

    @Test
    @DisplayName("后缀是 .pdf 但内容不是 PDF → 400（魔数校验）")
    void magicMismatchIsBadRequest() {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf",
                "not-a-pdf-at-all".getBytes(StandardCharsets.US_ASCII));
        when(objectStore.sha256(any())).thenReturn("c".repeat(64));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller().importDocument(TENANT, null, "TENANT", SOURCE_ID,
                        TENANT_CALLER, null, REVIEWER, fake, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("魔数"),
                "必须明确提示魔数不符，而不是含糊的「格式错误」");
    }
}
