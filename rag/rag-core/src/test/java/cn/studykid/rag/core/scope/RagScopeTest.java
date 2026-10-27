package cn.studykid.rag.core.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RagScope} 的纯逻辑测试（不需要数据库）。
 *
 * <p><b>钉住什么</b>：哨兵值的<b>字面量</b>与判别函数的<b>保守方向</b>。
 * 两者错了都不会报错，只会让「全局桶」在应用眼里变成一个普通租户——
 * 前者表现为全局语料忽然对所有租户隐形，后者表现为租户上下文被放行成全局身份。
 * 与 SQL 侧 {@code rag_global_tenant_id()} 的一致性由
 * {@code JdbcDictionaryRepositoryIT#sentinelConstantMatchesDatabase} 在真库上核对
 * （纯单测够不着数据库，这里只钉字面量，别把两边搞成两个真源）。
 */
class RagScopeTest {

    @Test
    @DisplayName("哨兵是 nil UUID 字面量：与 SQL 侧函数同值，改一处必须改两处")
    void sentinelLiteral() {
        assertEquals("00000000-0000-0000-0000-000000000000", RagScope.GLOBAL_TENANT_ID);
        assertTrue(RagScope.isGlobalTenant(RagScope.GLOBAL_TENANT_ID));
    }

    @Test
    @DisplayName("空白与大小写变体同样识别：租户 ID 来自请求头，不接受任何写法漏网")
    void variantsAreRecognised() {
        assertTrue(RagScope.isGlobalTenant("  " + RagScope.GLOBAL_TENANT_ID + "  "));
        assertTrue(RagScope.isGlobalTenant(
                "00000000-0000-0000-0000-000000000000".toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    @DisplayName("普通租户 ID 与空值都不是全局作用域")
    void ordinaryValuesAreNotGlobal() {
        assertFalse(RagScope.isGlobalTenant(null));
        assertFalse(RagScope.isGlobalTenant(""));
        assertFalse(RagScope.isGlobalTenant("   "));
        assertFalse(RagScope.isGlobalTenant("11111111-1111-1111-1111-111111111111"));
        // 形近不等于相同：只挡「恰好是哨兵」，不做前缀/子串匹配（否则真租户会被误拒）。
        assertFalse(RagScope.isGlobalTenant("00000000-0000-0000-0000-000000000001"));
        assertFalse(RagScope.isGlobalTenant(RagScope.GLOBAL_TENANT_ID.substring(0, 20)));
    }
}
