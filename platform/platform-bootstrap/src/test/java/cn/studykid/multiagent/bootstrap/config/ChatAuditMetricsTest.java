package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.application.agentchataudit.ChatAuditRetentionService;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.State;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecord;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditWriterPort;
import cn.studykid.multiagent.worker.retention.ChatAuditRetentionJob;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 对话审计指标的注册与语义验证（P4）。
 *
 * <p><b>为什么指标也要测</b>：审计是 fail-open 的（丢记录、吞 sink 异常），
 * 因此「审计在丢数据」与「审计一切正常」在业务层完全不可区分。
 * 指标是唯一证据——而一个注册失败或语义错位的指标，
 * 会让排障者看到一条恒为 0 的曲线并得出「一切正常」的结论。
 */
@DisplayName("对话审计指标")
class ChatAuditMetricsTest {

    private static final class StubWriter implements ChatAuditWriterPort {
        final AtomicLong submitted = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final AtomicLong sinkErrors = new AtomicLong();
        final AtomicLong written = new AtomicLong();

        @Override
        public void submit(@NonNull ChatAuditRecord record, boolean async) {
            submitted.incrementAndGet();
        }

        @Override
        public long submittedTotal() {
            return submitted.get();
        }

        @Override
        public long droppedTotal() {
            return dropped.get();
        }

        @Override
        public long sinkErrorTotal() {
            return sinkErrors.get();
        }

        @Override
        public long writtenTotal() {
            return written.get();
        }
    }

    private static ChatAuditConfigPort configPort(boolean enabled) {
        ChatAuditConfig current = enabled ? ChatAuditConfig.defaults()
                : new ChatAuditConfig(false, ChatAuditConfig.defaults().scope(),
                        ChatAuditConfig.defaults().sinks(),
                        ChatAuditConfig.defaults().capture(),
                        ChatAuditConfig.defaults().redaction(),
                        ChatAuditConfig.defaults().sampling(),
                        ChatAuditConfig.defaults().writer(),
                        ChatAuditConfig.defaults().retention(),
                        ChatAuditConfig.defaults().limits());
        return new ChatAuditConfigPort() {
            @Override
            public @NonNull State get() {
                return new State(current, 1L, "test", null);
            }

            @Override
            public @NonNull State save(@NonNull ChatAuditConfig config,
                                                        @NonNull String actorId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** 配置表不可读的端口（模拟 DB 故障）。 */
    private static ChatAuditConfigPort unreadableConfigPort() {
        return new ChatAuditConfigPort() {
            @Override
            public @NonNull State get() {
                throw new IllegalStateException("配置表不可读");
            }

            @Override
            public @NonNull State save(@NonNull ChatAuditConfig config,
                                                        @NonNull String actorId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @DisplayName("四个写入侧计数按端口实时读取（拉模型，不在写入侧累加）")
    void writerGaugesReflectPortCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StubWriter writer = new StubWriter();
        new ChatAuditMetrics(registry, writer, configPort(true), null);

        writer.submitted.set(10);
        writer.dropped.set(2);
        writer.sinkErrors.set(3);
        writer.written.set(7);

        assertThat(registry.get(ChatAuditMetrics.SUBMITTED).gauge().value()).isEqualTo(10d);
        assertThat(registry.get(ChatAuditMetrics.DROPPED).gauge().value()).isEqualTo(2d);
        assertThat(registry.get(ChatAuditMetrics.SINK_ERRORS).gauge().value()).isEqualTo(3d);
        assertThat(registry.get(ChatAuditMetrics.WRITTEN).gauge().value()).isEqualTo(7d);
    }

    @Test
    @DisplayName("⛔ 开关三态：1=开 / 0=关 / -1=配置读不到（不得把故障伪装成「已关闭」）")
    void enabledFlagDistinguishesUnreadableFromOff() {
        assertThat(ChatAuditMetrics.enabledValue(configPort(true))).isEqualTo(1d);
        assertThat(ChatAuditMetrics.enabledValue(configPort(false)))
                .as("⛔ 关闭态与开启态取同值：面板上无法区分「关了」与「在记」")
                .isZero();
        assertThat(ChatAuditMetrics.enabledValue(unreadableConfigPort()))
                .as("⛔ 配置故障被映射成 0：会伪装成「运维主动关掉了审计」，排障方向直接走偏")
                .isEqualTo(-1d);
    }

    @Test
    @DisplayName("清理任务接入后额外注册删除量/失败类别三项（失败类别是合规告警出口）")
    void retentionJobGaugesRegisteredWhenPresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChatAuditRetentionJob job = new ChatAuditRetentionJob(
                new ChatAuditRetentionService(null, null, ChatAuditConfig::defaults, null,
                        "retention-job", 100, java.time.Clock.systemUTC()),
                List.of("11111111-1111-4111-8111-111111111111"),
                java.time.Duration.ofHours(1));

        new ChatAuditMetrics(registry, new StubWriter(), configPort(true), job);

        assertThat(registry.get(ChatAuditMetrics.RETENTION_ROWS).gauge().value()).isZero();
        assertThat(registry.get(ChatAuditMetrics.RETENTION_FILES).gauge().value()).isZero();
        assertThat(registry.get(ChatAuditMetrics.RETENTION_FAILURES).gauge().value()).isZero();
    }

    @Test
    @DisplayName("未装配 MeterRegistry 时不注册也不抛（审计仍工作，只是丢数据倍数不可观测）")
    void missingRegistryIsNotFatal() {
        new ChatAuditMetrics(null, new StubWriter(), configPort(true), null);
        // 构造完成即通过：说明缺指标出口不会让聊天链路起不来
        assertThat(true).isTrue();
    }
}
