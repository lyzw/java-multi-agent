package cn.studykid.rag.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cn.studykid.rag.application.ingest.GlobalIngestJobOrchestrator;
import cn.studykid.rag.core.ingest.IngestJob;
import cn.studykid.rag.core.ingest.IngestJobStatus;
import cn.studykid.rag.ports.IngestJobPort;

/**
 * 平台档（全局桶）摄取消费循环的失败形态测试。
 *
 * <p>⚠️ 这里守的不是「 happy path 能跑」，而是<b>调度线程绝不死亡</b>：
 * {@code @Scheduled} 单线程一旦因未捕获异常退出，后续所有 job 会永远停在
 * PENDING 且没有任何错误日志——与 V1.0.2 之前「忘开 @EnableScheduling」
 * 是同一种静默失败。因此每个异常分支都断言「不向外抛」。
 */
class GlobalIngestJobConsumerTest {

    private static final String JOB_ID = "job-global-1";
    private static final String SOURCE_ID = "source-global-1";

    private static IngestJob job() {
        return new IngestJob(
                JOB_ID,
                "00000000-0000-0000-0000-000000000000",
                SOURCE_ID,
                IngestJobStatus.PARSING,
                "PARSING 30",
                0,
                null, null, null, null, null, null, null,
                "2026-11-05T00:00:00Z");
    }

    private static GlobalIngestJobConsumer consumer(IngestJobPort port,
                                                     GlobalIngestJobOrchestrator orchestrator) {
        return new GlobalIngestJobConsumer(port, orchestrator);
    }

    @Test
    void 没有待处理任务时不应触碰编排器() {
        IngestJobPort port = mock(IngestJobPort.class);
        GlobalIngestJobOrchestrator orchestrator = mock(GlobalIngestJobOrchestrator.class);
        when(port.claimNextGlobalJob()).thenReturn(null);

        assertDoesNotThrow(() -> consumer(port, orchestrator).pollOnce());

        verifyNoInteractions(orchestrator);
        verify(port, never()).markFailedGlobal(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 领到的任务交给全局编排器执行() {
        IngestJobPort port = mock(IngestJobPort.class);
        GlobalIngestJobOrchestrator orchestrator = mock(GlobalIngestJobOrchestrator.class);
        IngestJob claimed = job();
        when(port.claimNextGlobalJob()).thenReturn(claimed);

        assertDoesNotThrow(() -> consumer(port, orchestrator).pollOnce());

        verify(orchestrator).run(claimed);
    }

    @Test
    void 领取失败不得终止调度线程() {
        IngestJobPort port = mock(IngestJobPort.class);
        GlobalIngestJobOrchestrator orchestrator = mock(GlobalIngestJobOrchestrator.class);
        when(port.claimNextGlobalJob()).thenThrow(new IllegalStateException("运维连接抖动"));

        // ⚠️ 断言重点是「不抛」：抛了就等价于调度线程死亡。
        assertDoesNotThrow(() -> consumer(port, orchestrator).pollOnce());

        verifyNoInteractions(orchestrator);
    }

    @Test
    void 编排器崩溃时尽力补标FAILED且原因可读() {
        IngestJobPort port = mock(IngestJobPort.class);
        GlobalIngestJobOrchestrator orchestrator = mock(GlobalIngestJobOrchestrator.class);
        IngestJob claimed = job();
        when(port.claimNextGlobalJob()).thenReturn(claimed);
        doThrow(new IllegalStateException("嵌入服务不可用"))
                .when(orchestrator).run(claimed);

        assertDoesNotThrow(() -> consumer(port, orchestrator).pollOnce());

        // ⚠️ FAILED 必带可读原因：没有 error_detail 的 FAILED 在录入工作台无法处置。
        verify(port).markFailedGlobal(eq(JOB_ID), contains("CONSUMER_CRASH"));
        verify(port).markFailedGlobal(eq(JOB_ID), contains("嵌入服务不可用"));
    }

    @Test
    void 连失败回写都失败时也不得抛出() {
        IngestJobPort port = mock(IngestJobPort.class);
        GlobalIngestJobOrchestrator orchestrator = mock(GlobalIngestJobOrchestrator.class);
        IngestJob claimed = job();
        when(port.claimNextGlobalJob()).thenReturn(claimed);
        doThrow(new IllegalStateException("解析崩溃")).when(orchestrator).run(claimed);
        doThrow(new IllegalStateException("运维库不可用"))
                .when(port).markFailedGlobal(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        assertDoesNotThrow(() -> consumer(port, orchestrator).pollOnce());

        verify(port).markFailedGlobal(eq(JOB_ID), contains("CONSUMER_CRASH"));
    }
}
