package cn.studykid.rag.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.infrastructure.ops.RagOpsUnavailableException;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 僵尸清扫器守卫：平台全局桶必须被独立清扫（2026-11-10 页面端导入实测缺陷）。
 *
 * <p><b>⚠️ 反向验证</b>：把 {@code IngestJobSweeper.sweep()} 里的
 * {@code sweepGlobalJobs()} 调用删掉（即修复前形态），
 * {@link #全局清扫必须独立于租户清单执行()} 会因
 * {@code verify(jobPort).failTimedOutRunningGlobalJobs(...)} 未发生而立刻变红；
 * 只把全局清扫塞进租户 try 块、不做独立异常兜底，则
 * {@link #运维通道未接入时全局清扫静默跳过且不影响租户()} 变红。
 *
 * <p>⚠️ 这里守的是「调度线程不死亡 + 全局卡死 job 有人接管」，
 * 而非 SQL 正确性（后者由 {@code GlobalIngestRepositoryTest} 的桶谓词守卫覆盖）。
 */
class IngestJobSweeperTest {

    private static IngestJobSweeper sweeper(IngestJobPort jobPort, List<String> tenants) {
        RagIndexerProperties properties = new RagIndexerProperties();
        properties.setTenants(tenants);
        return new IngestJobSweeper(jobPort, properties);
    }

    @Test
    @DisplayName("全局清扫必须独立于租户清单执行（租户为空也要扫）")
    void 全局清扫必须独立于租户清单执行() {
        IngestJobPort port = mock(IngestJobPort.class);
        when(port.failTimedOutRunningGlobalJobs(anyInt())).thenReturn(0);

        // 租户清单为空：修复前的 sweep() 会直接 return，全局 job 永不被接管
        assertDoesNotThrow(() -> sweeper(port, List.of()).sweep());

        verify(port).failTimedOutRunningGlobalJobs(anyInt());
        verify(port, never()).failTimedOutRunningJobs(anyString(), anyInt());
    }

    @Test
    @DisplayName("运维通道未接入时全局清扫静默跳过且不影响租户")
    void 运维通道未接入时全局清扫静默跳过且不影响租户() {
        IngestJobPort port = mock(IngestJobPort.class);
        when(port.failTimedOutRunningJobs(anyString(), anyInt())).thenReturn(1);
        doThrow(new RagOpsUnavailableException("rag.ops.enabled", "通道未接入"))
                .when(port).failTimedOutRunningGlobalJobs(anyInt());

        // ⚠️ 断言重点：运维通道缺席（平台档未启用的正常部署）不能让调度线程死亡，
        //    也不能阻断租户桶清扫。
        assertDoesNotThrow(() -> sweeper(port, List.of(
                "11111111-1111-1111-1111-111111111111")).sweep());

        verify(port).failTimedOutRunningJobs(anyString(), anyInt());
    }

    @Test
    @DisplayName("全局清扫运行时异常 → 记日志但不终止调度线程")
    void 全局清扫异常不得终止调度线程() {
        IngestJobPort port = mock(IngestJobPort.class);
        doThrow(new IllegalStateException("连接池抖动"))
                .when(port).failTimedOutRunningGlobalJobs(anyInt());

        assertDoesNotThrow(() -> sweeper(port, List.of()).sweep());

        verify(port).failTimedOutRunningGlobalJobs(anyInt());
    }

    @Test
    @DisplayName("全局与租户清扫都发生时各记一次，互不吞掉")
    void 全局与租户清扫互不吞掉() {
        IngestJobPort port = mock(IngestJobPort.class);
        when(port.failTimedOutRunningJobs(anyString(), anyInt())).thenReturn(2);
        when(port.failTimedOutRunningGlobalJobs(anyInt())).thenReturn(1);

        assertDoesNotThrow(() -> sweeper(port, List.of(
                "11111111-1111-1111-1111-111111111111")).sweep());

        verify(port).failTimedOutRunningJobs(anyString(), anyInt());
        // 护栏：全局清扫恰好一次，不被租户清单长度叠加（各扫各的）
        verify(port, times(1)).failTimedOutRunningGlobalJobs(anyInt());
    }

    @Test
    @DisplayName("全局租户哨兵本身不进租户循环（避免重复扫）")
    void 全局哨兵不进租户循环() {
        IngestJobPort port = mock(IngestJobPort.class);
        when(port.failTimedOutRunningGlobalJobs(anyInt())).thenReturn(0);
        String globalSentinel = cn.studykid.rag.core.scope.RagScope.GLOBAL_TENANT_ID;

        sweeper(port, List.of(globalSentinel)).sweep();

        // 租户侧一次都不该扫全局哨兵（那是全局清扫的职责），全局清扫独立一次
        verify(port, never()).failTimedOutRunningJobs(anyString(), anyInt());
        verify(port).failTimedOutRunningGlobalJobs(anyInt());
    }
}
