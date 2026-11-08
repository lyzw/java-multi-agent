package cn.studykid.multiagent.worker.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.ports.messaging.MessageSender;
import cn.studykid.multiagent.ports.observer.ObservableModelGateway;
import cn.studykid.multiagent.ports.persistence.OutboxMessage;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;

/**
 * P0 自动采集接线（G2）：验证 Run 终态调用的 {@code publishTraceCollect}
 * 会把采集事件发到<b>专用队列</b> {@code agent.trace.collect}（绝不复用执行队列），
 * 且携带正确的 tenantId / runId。
 */
class RunExecutionLoopTracePublishTest {

    private RunExecutionLoop loopWith(MessageSender sender) {
        return new RunExecutionLoop(
                mock(ObservableModelGateway.class),   // 1 modelGateway
                mock(RunClaimPort.class),           // 2 claimPort
                mock(ToolInvocationGate.class),     // 3 toolGate
                Duration.ofSeconds(30),             // 4 leaseDuration
                "test-worker",                      // 5 workerId
                mock(PromptGuard.class),            // 6 promptGuard
                null,                               // 7 eventPort
                null,                               // 8 adopter
                1000,                               // 9 contextTokenBudget
                null,                               // 10 lifecycleProbe
                null,                               // 11 stepPort
                null,                               // 12 riskEventPort
                null,                               // 13 observerChain
                null,                               // 14 preemptionExecutor
                null,                               // 15 budget
                null,                               // 16 pricing
                null,                               // 17 continuation
                null,                               // 18 memoryRecallService
                null,                               // 19 memoryConsolidator
                sender);                            // 20 traceSender
    }

    @Test
    void publishTraceCollect_sendsToDedicatedQueue_withTenantAndRun() {
        MessageSender sender = mock(MessageSender.class);
        RunClaimPort.LeasedTask task = mock(RunClaimPort.LeasedTask.class);
        String runId = UUID.randomUUID().toString();
        String tenantId = "tenant-abc";
        org.mockito.Mockito.when(task.runId()).thenReturn(runId);
        org.mockito.Mockito.when(task.fencingToken()).thenReturn(7L);

        loopWith(sender).publishTraceCollect(tenantId, task);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(sender).send(eq("agent.trace.collect"), captor.capture());
        OutboxMessage msg = captor.getValue();
        assertEquals(runId, msg.runId());
        assertEquals(tenantId, msg.tenantId());
        assertEquals(7L, msg.fencingToken());
    }

    @Test
    void publishTraceCollect_nullSender_isNoOp() {
        RunClaimPort.LeasedTask task = mock(RunClaimPort.LeasedTask.class);
        org.mockito.Mockito.when(task.runId()).thenReturn(UUID.randomUUID().toString());
        org.mockito.Mockito.when(task.fencingToken()).thenReturn(1L);

        // traceSender = null → 不应抛异常（退化为「不发布」，由补算按钮兜底）
        assertDoesNotThrow(() -> loopWith(null).publishTraceCollect("t", task));
    }
}
