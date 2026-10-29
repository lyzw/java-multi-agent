package cn.studykid.rag.core.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RagCorpusScope} 的纯逻辑测试（不需要数据库）。
 *
 * <p><b>钉住什么</b>：作用域是「这次到底查哪一批行」的唯一权威声明，
 * 它的解析规则错了不会报错，只会让管理员看错批次——
 * 尤其「非法值静默回落成租户档」这一条，会让「以为在看全局、实际在看租户」
 * 完全不可见（RAG-D11 第四刀刻意反过来做：错值必须响）。
 */
class RagCorpusScopeTest {

    @Test
    @DisplayName("缺省档是 TENANT：不带头等于「以租户身份治理本租户语料」")
    void blankHeaderDefaultsToTenant() {
        assertEquals(RagCorpusScope.TENANT, RagCorpusScope.parse(null));
        assertEquals(RagCorpusScope.TENANT, RagCorpusScope.parse(""));
        assertEquals(RagCorpusScope.TENANT, RagCorpusScope.parse("   "));
    }

    @Test
    @DisplayName("头名与档位字面量是对外契约：改名即破坏调用方")
    void headerAndLiteralAreContract() {
        assertEquals("X-Rag-Scope", RagCorpusScope.HEADER);
        assertEquals(RagCorpusScope.PLATFORM, RagCorpusScope.parse("PLATFORM"));
        assertEquals(RagCorpusScope.ALL, RagCorpusScope.parse("ALL"));
    }

    @Test
    @DisplayName("大小写与首尾空格容忍：头部传输习惯不该改变治理结论")
    void tolerantOfCaseAndSurroundingSpace() {
        assertEquals(RagCorpusScope.PLATFORM, RagCorpusScope.parse(" platform "));
    }

    @Test
    @DisplayName("⛔ 非法值报错而非回落：拼错的档位不能悄悄变成租户档")
    void illegalValueIsRejected() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> RagCorpusScope.parse("PLATFORME"));
        assertTrue(ex.getMessage().contains("PLATFORME"),
                "错误消息要带回原始错值，否则调用方无从定位：" + ex.getMessage());
    }

    @Test
    @DisplayName("只有租户档接受租户令牌：另两档需要平台域身份")
    void onlyTenantAcceptsTenantToken() {
        assertFalse(RagCorpusScope.TENANT.requiresPlatformCaller());
        assertTrue(RagCorpusScope.PLATFORM.requiresPlatformCaller());
        assertTrue(RagCorpusScope.ALL.requiresPlatformCaller());
    }
}
