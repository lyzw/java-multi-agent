package cn.studykid.multiagent.infrastructure.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.messaging.MessageHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 队列消费端单测（P1-13 / P1-14）。
 *
 * <p>⚠️ <b>本类只测「不需要真库」的那一层</b>：消费端的分支判定与围栏参数传递。
 * 仓储层的 SQL（回收计数、{@code claimed_by} 围栏）由 {@code PgQueueIT} 覆盖，
 * 而那组用例需要真实 PostgreSQL。
 *
 * <p>⚠️ <b>P1-13 的根因</b>：可见性超时回收（{@code claim} 领回超时的 INFLIGHT 行）
 * <b>不增加 attempts</b>，于是「消费者进程崩溃 → 行停在 INFLIGHT → 被回收重投」这条路
 * 永远达不到重试上限：<b>毒消息永不进死信</b>，只是无限重投，
 * 而且系统表面完全正常（与本项目已记录的「断链」形态同族）。
 *
 * <p>⚠️ <b>P1-14 的根因</b>：{@code ack} / {@code retry} / {@code reject}
 * 只按 {@code (queue_id, tenant_id)} 定位行，<b>不校验当前持有者</b>。
 * 于是持有者超时被回收后，旧持有者的 ack 会删掉新持有者正在处理的行，
 * 旧持有者的 retry 会把新持有者正在处理的行退回 PENDING（<b>重复执行</b>）。
 */
class PgQueueListenerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    /** 记录调用的仓储替身。 */
    private static final class FakeQueueRepository extends PgQueueRepository {

        final List<QueuedMessage> toDeliver = new ArrayList<>();
        final List<String> ackedBy = new ArrayList<>();
        final List<String> retriedBy = new ArrayList<>();
        final List<String> rejectedBy = new ArrayList<>();
        final List<UUID> deadLettered = new ArrayList<>();
        int handledCalls;

        FakeQueueRepository() {
            super(null, null);
        }

        @Override
        public List<QueuedMessage> claim(String tenantId, int limit,
                                         Duration visibilityTimeout, String workerId) {
            List<QueuedMessage> out = new ArrayList<>(toDeliver);
            toDeliver.clear();
            return out;
        }

        @Override
        public void ack(String tenantId, UUID queueId, String workerId) {
            ackedBy.add(workerId);
        }

        @Override
        public void retry(String tenantId, UUID queueId, String error,
                          Instant nextAttemptAt, String workerId) {
            retriedBy.add(workerId);
        }

        @Override
        public void reject(String tenantId, UUID queueId, String eventType, String runId,
                           Map<String, Object> payload, int attempts, String error,
                           String workerId) {
            rejectedBy.add(workerId);
            deadLettered.add(queueId);
        }
    }

    private static PgQueueRepository.QueuedMessage message(int attempts) {
        return new PgQueueRepository.QueuedMessage(
                UUID.randomUUID(), TENANT, "run-1", "RUN_DISPATCH",
                Map.of("runId", "run-1"), attempts);
    }

    private static PgQueueListener listener(PgQueueRepository repository,
                                            MessageHandler handler, int maxAttempts) {
        return new PgQueueListener(List.of(TENANT), repository, handler,
                Duration.ofMillis(50), 10, maxAttempts,
                Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("⚠️⚠️ 回收次数已达上限 → 直接进死信，不再投递（P1-13 反证）")
    void reclaimedBeyondLimitGoesStraightToDeadLetter() {
        FakeQueueRepository repository = new FakeQueueRepository();
        PgQueueRepository.QueuedMessage msg = message(3);
        repository.toDeliver.add(msg);

        MessageHandler handler = (eventType, payload, workerId) -> {
            repository.handledCalls++;
            return MessageHandler.Outcome.CLAIMED;
        };

        // maxAttempts = 3 → attempts 已达 3 的消息不得再被投递
        int processed = listener(repository, handler, 3).pollOnce();

        assertThat(repository.handledCalls)
                .as("⚠️⚠️ 已达上限的消息不得再交给处理器 —— "
                        + "处理器的异常/挂起路径都到不了「上限检查」，"
                        + "毒消息会无限回收重投、永远不进死信")
                .isZero();
        assertThat(repository.deadLettered)
                .as("⚠️⚠️ 必须直接进死信（死信表是「只进不出」的最终出口）")
                .containsExactly(msg.queueId());
        assertThat(processed).isZero();
    }

    @Test
    @DisplayName("✅ 未达上限的消息正常投递并 ack")
    void belowLimitIsDelivered() {
        FakeQueueRepository repository = new FakeQueueRepository();
        PgQueueRepository.QueuedMessage msg = message(1);
        repository.toDeliver.add(msg);

        MessageHandler handler = (eventType, payload, workerId) -> {
            repository.handledCalls++;
            return MessageHandler.Outcome.CLAIMED;
        };

        int processed = listener(repository, handler, 3).pollOnce();

        assertThat(repository.handledCalls).isEqualTo(1);
        assertThat(repository.ackedBy).hasSize(1);
        assertThat(repository.deadLettered).isEmpty();
        assertThat(processed).isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️⚠️ ack / retry / reject 必须带上 workerId（claimed_by 围栏的前提）")
    void allTerminalActionsCarryWorkerId() {
        FakeQueueRepository repository = new FakeQueueRepository();

        MessageHandler claimed = (eventType, payload, workerId) -> MessageHandler.Outcome.CLAIMED;
        repository.toDeliver.add(message(0));
        listener(repository, claimed, 3).pollOnce();

        FakeQueueRepository retryRepo = new FakeQueueRepository();
        retryRepo.toDeliver.add(message(0));
        MessageHandler retryOutcome = (eventType, payload, workerId) -> MessageHandler.Outcome.RETRY;
        listener(retryRepo, retryOutcome, 3).pollOnce();

        FakeQueueRepository rejectRepo = new FakeQueueRepository();
        rejectRepo.toDeliver.add(message(0));
        MessageHandler rejectOutcome =
                (eventType, payload, workerId) -> MessageHandler.Outcome.REJECTED;
        listener(rejectRepo, rejectOutcome, 3).pollOnce();

        assertThat(repository.ackedBy).hasSize(1);
        assertThat(repository.ackedBy.get(0))
                .as("⚠️⚠️ ack 必须带 workerId —— 仓储层据此加 claimed_by 围栏，"
                        + "否则超时的旧持有者会删掉新持有者正在处理的行")
                .isNotBlank();
        assertThat(retryRepo.retriedBy).hasSize(1);
        assertThat(retryRepo.retriedBy.get(0)).isNotBlank();
        assertThat(rejectRepo.rejectedBy).hasSize(1);
        assertThat(rejectRepo.rejectedBy.get(0)).isNotBlank();
    }
}
