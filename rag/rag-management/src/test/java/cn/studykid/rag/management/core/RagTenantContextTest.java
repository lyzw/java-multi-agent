package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.core.source.TrustTier;

/**
 * 租户上下文构造的守卫测试（RAG-D00 §4「租户与身份」边界）。
 *
 * <p><b>⚠️ 为什么这一条值得单测</b>：V8 把「全局」表示成一个**真实可传的 UUID**
 * （哨兵），于是「调用方把 app.tenant_id 设成哨兵」成为一条新的越权路径——
 * 一旦成立，该会话就能以普通租户身份读写全局桶。
 * 数据库侧已由 {@code rag_current_tenant()} 抛错封死（反证脚本 P12 实测），
 * 这里是应用侧的第二层：越权请求应在进入业务前就变成 4xx，
 * 而不是变成一个「看起来正常」的租户会话。
 */
class RagTenantContextTest {

    @Test
    @DisplayName("哨兵租户 ID 不能当作会话身份")
    void globalSentinelRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new RagTenantContext(RagScope.GLOBAL_TENANT_ID, TrustTier.OFFICIAL_TUTORIAL));
        assertTrue(e.getMessage().contains("全局"),
                "报错要说明「为什么」，否则调用方只会去查 UUID 格式问题：" + e.getMessage());
    }

    @Test
    @DisplayName("空白租户 ID 被拒；正常租户 ID 原样保留")
    void ordinaryValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new RagTenantContext("  ", TrustTier.OFFICIAL_TUTORIAL));
        assertThrows(IllegalArgumentException.class,
                () -> new RagTenantContext("11111111-1111-1111-1111-111111111111", null));
        RagTenantContext ctx =
                new RagTenantContext("11111111-1111-1111-1111-111111111111", TrustTier.OFFICIAL_TUTORIAL);
        assertEquals("11111111-1111-1111-1111-111111111111", ctx.tenantId());
        assertEquals(TrustTier.OFFICIAL_TUTORIAL, ctx.minTrustTier());
    }
}
