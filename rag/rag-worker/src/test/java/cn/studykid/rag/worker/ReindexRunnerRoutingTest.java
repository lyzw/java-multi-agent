package cn.studykid.rag.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.studykid.rag.core.scope.RagScope;
import cn.studykid.rag.infrastructure.indexing.ChunkIndexer;
import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;

/**
 * 启动重索引的<b>通道分流</b>测试（第二刀 2.5）。
 *
 * <p><b>⚠️ 这里只测一件事：哨兵租户走哪条通道</b>。
 * 把全局哨兵当普通租户喂给 {@code indexTenant} 的后果不是「权限不够」，
 * 而是事务内 {@code set_config('app.tenant_id', 哨兵)} 之后
 * {@code rag_current_tenant()} 直接 RAISE（SQLSTATE 42501）——
 * 表现为启动日志里一条数据库异常，谁也看不出真正的问题是「该走运维通道」。</p>
 */
class ReindexRunnerRoutingTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    private final ChunkIndexer chunkIndexer = mock(ChunkIndexer.class);
    private final RagIndexerProperties properties = new RagIndexerProperties();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private ReindexRunner runnerWith(List<String> tenants) {
        // fail-closed 探针读的是「无租户会话下可见的租户私有行数」，这里让它返回 0
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        properties.setTenants(tenants);
        return new ReindexRunner(chunkIndexer, properties, jdbcTemplate);
    }

    @Test
    @DisplayName("列表里的全局哨兵走全局通道，其余走租户通道")
    void sentinelEntryRoutesToGlobalChannel() {
        when(chunkIndexer.indexTenant(anyString())).thenReturn(3);
        when(chunkIndexer.indexGlobalCorpus(anyInt()))
                .thenReturn(new ChunkIndexer.IndexingSummary(7, 7));
        runnerWith(List.of(TENANT_A, RagScope.GLOBAL_TENANT_ID, TENANT_B))
                .run(new DefaultApplicationArguments(new String[0]));

        verify(chunkIndexer).indexTenant(eq(TENANT_A));
        verify(chunkIndexer).indexTenant(eq(TENANT_B));
        verify(chunkIndexer).indexGlobalCorpus(eq(Integer.MAX_VALUE));
        // ⚠️ 承重断言：哨兵一次都没进过租户通道
        verify(chunkIndexer, never()).indexTenant(eq(RagScope.GLOBAL_TENANT_ID));
    }

    @Test
    @DisplayName("运维通道未接入时全局条目记失败并继续处理其余租户")
    void unavailableOpsChannelDoesNotAbortTheWholeRun() {
        when(chunkIndexer.indexTenant(anyString())).thenReturn(2);
        when(chunkIndexer.indexGlobalCorpus(anyInt())).thenThrow(
                new RagOpsUnavailableException("rag.ops.datasource", "全局语料索引通道未接入"));

        ReindexRunner runner = runnerWith(List.of(RagScope.GLOBAL_TENANT_ID, TENANT_A));

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments(new String[0])));
        verify(chunkIndexer).indexTenant(eq(TENANT_A));
    }

    @Test
    @DisplayName("不配置哨兵时行为与改造前完全一致（只走租户通道）")
    void tenantOnlyListKeepsTheOldPath() {
        when(chunkIndexer.indexTenant(anyString())).thenReturn(1);
        runnerWith(List.of(TENANT_A)).run(new DefaultApplicationArguments(new String[0]));

        verify(chunkIndexer).indexTenant(eq(TENANT_A));
        verify(chunkIndexer, never()).indexGlobalCorpus(anyInt());
    }
}
