package cn.studykid.multiagent.infrastructure.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecord;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.BeginContext;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort.Session;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditWriterPort;

/**
 * 对话审计<b>两阶段收敛</b>的守卫：终态（{@code finish}）与 HTTP 侧事实（{@code seal}）
 * 谁先到都不能让记录残缺。
 *
 * <p><b>为什么值得单独立测</b>：SSE 把编排甩到虚拟线程，装饰器经常在 HTTP 过滤器
 * 补齐状态码 / 原始 body <b>之前</b>收敛。若"先到先写"，产出的记录会是
 * {@code terminalState=SUCCEEDED} 但 {@code http.status=0}、{@code request.rawBody=null} ——
 * 看起来完整，实际还原不了客户端发了什么。这类缺陷只在真实链路上偶发，
 * 单测必须显式钉住两种到达顺序。
 *
 * <p><b>反向验证锚点</b>：把 {@code flushIfReady} 的条件改成只判终态（不等 seal）
 * → 用例 1 红（记录提前写出、缺状态码）；把 {@code seal()} 改成直接 flush 不置标志
 * → 用例 2 红（seal 先到也写了半截记录）。
 */
@DisplayName("对话审计两阶段收敛：终态与 HTTP 事实任意顺序到达都不残缺")
class ChatAuditRecorderTwoPhaseTest {

    private final List<ChatAuditRecord> written = new ArrayList<>();

    private final ChatAuditWriterPort writer = new ChatAuditWriterPort() {
        @Override
        public void submit(ChatAuditRecord record, boolean async) {
            written.add(record);
        }

        @Override
        public long submittedTotal() {
            return written.size();
        }

        @Override
        public long droppedTotal() {
            return 0L;
        }

        @Override
        public long sinkErrorTotal() {
            return 0L;
        }

        @Override
        public long writtenTotal() {
            return written.size();
        }
    };

    @Test
    @DisplayName("⛔ 装饰器先收敛（seal 未到）：绝不能先写出一条缺状态码/缺原文的记录")
    void finishBeforeSealDoesNotWritePrematurely() {
        Session session = newSession();

        session.recordRequest(new ChatAuditEnvelope.RequestInfo(
                null, "怎么移项", List.of(), null, null, true, null, null, null, List.of()), false);
        session.recordChunk("delta", "先移项");
        session.finish("SUCCEEDED", null);

        assertThat(written)
                .as("⛔ 终态一到就落库：SSE 短流会写出 http.status=0、rawBody 为空的残缺记录")
                .isEmpty();

        // HTTP 侧事实晚到：状态码 + 原始 body 补齐后才允许落库
        session.setHttpStatus(200);
        session.setRawRequestBody("{\"message\":\"怎么移项\"}", false);
        session.seal();

        assertThat(written).hasSize(1);
        ChatAuditEnvelope envelope = (ChatAuditEnvelope) written.getFirst();
        assertThat(envelope.http().status()).isEqualTo(200);
        assertThat(envelope.request()).isNotNull();
        assertThat(envelope.request().rawBody()).contains("怎么移项");
        assertThat(envelope.terminalState()).isEqualTo("SUCCEEDED");
        assertThat(envelope.chunks()).hasSize(1);
    }

    @Test
    @DisplayName("✅ seal 先到、终态后到：也要等终态才写（不能写半截记录）")
    void sealBeforeFinishWaitsForTerminal() {
        Session session = newSession();

        session.setHttpStatus(200);
        session.setRawRequestBody("{\"message\":\"怎么移项\"}", false);
        session.seal();

        assertThat(written).as("⛔ seal 一到就落库：终态还是空的，记录无法判定成功/失败")
                .isEmpty();

        session.finish("FAILED", new ChatAuditEnvelope.ErrorInfo(
                "orchestrator", "ORCHESTRATOR_ERROR", "模型网关不可用"));

        assertThat(written).hasSize(1);
        ChatAuditEnvelope envelope = (ChatAuditEnvelope) written.getFirst();
        assertThat(envelope.terminalState()).isEqualTo("FAILED");
        assertThat(envelope.error()).isNotNull();
        assertThat(envelope.error().code()).isEqualTo("ORCHESTRATOR_ERROR");
        assertThat(envelope.http().status()).isEqualTo(200);
    }

    @Test
    @DisplayName("幂等：重复 finish / 重复 seal 只写一条（异步兜底与装饰器不会打架）")
    void repeatedConvergenceWritesOnce() {
        Session session = newSession();
        session.setHttpStatus(200);
        session.seal();
        session.finish("SUCCEEDED", null);
        session.finish("FAILED", new ChatAuditEnvelope.ErrorInfo("stream", "SSE_ERROR", "x"));
        session.seal();

        assertThat(written).hasSize(1);
        assertThat(((ChatAuditEnvelope) written.getFirst()).terminalState())
                .as("后到的终态不得覆盖先到的终态：先收敛的一方才是真实观察者")
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("终态登记后不再接受帧/分片（业务侧已关闭）")
    void framesAfterFinishAreIgnored() {
        Session session = newSession();
        session.finish("SUCCEEDED", null);
        session.recordChunk("delta", "迟到的分片");
        session.recordFrame("delta", "迟到的帧");
        session.setHttpStatus(200);
        session.seal();

        ChatAuditEnvelope envelope = (ChatAuditEnvelope) written.getFirst();
        assertThat(envelope.chunks()).isEmpty();
        assertThat(envelope.events()).isEmpty();
    }

    private Session newSession() {
        ChatAuditRecorder recorder = new ChatAuditRecorder(writer);
        return recorder.begin(new BeginContext(
                "11111111-2222-4333-8444-555555555555", "trace-two-phase",
                "/api/knowledge/agent/chat/stream", "TENANT",
                "22222222-3333-4444-8555-666666666666", "stu_principal",
                Instant.parse("2026-11-18T10:00:00Z"), "POST",
                "/api/knowledge/agent/chat/stream", Map.of(), config(), 7L));
    }

    /** 全开配置（采样 1.0、异步关闭：异步队列会引入与本测无关的时序）。 */
    private static ChatAuditConfig config() {
        ChatAuditConfig d = ChatAuditConfig.defaults();
        return new ChatAuditConfig(d.enabled(), d.scope(), d.sinks(), d.capture(), d.redaction(),
                d.sampling(),
                new ChatAuditConfig.Writer(false, 100, ChatAuditConfig.Writer.IGNORE, 1000),
                d.retention(), d.limits());
    }
}
