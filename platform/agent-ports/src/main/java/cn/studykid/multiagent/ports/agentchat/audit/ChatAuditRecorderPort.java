package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.ErrorInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.RequestInfo;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Result;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope.Turn;

/**
 * 对话审计录制器端口（单次请求一个会话句柄）。
 *
 * <p><b>⚠️ 两段式</b>：{@link #begin} 在请求进入时开会话，返回句柄；
 * 业务期间通过句柄追加帧 / 分片 / 请求信息；{@link Session#finish} 收敛终态并提交异步写入。
 *
 * <p><b>⚠️ fail-open 铁律</b>：本端口所有方法<b>绝不向业务抛异常</b>。
 * 录制失败只记内部计数并 WARN——决策 6「审计不影响业务」。
 * 关闭态返回 {@link #NOOP}。
 */
public interface ChatAuditRecorderPort {

    /**
     * 开启一次录制会话。
     *
     * @param context 请求入口快照（身份 / traceId / 端点 / 配置版本），在请求线程构造
     * @return 会话句柄（关闭态返回 no-op 句柄，调用方无需判空）
     */
    @NonNull Session begin(@NonNull BeginContext context);

    /** 关闭态单例录制器（装配缺位时使用，所有方法 no-op）。 */
    ChatAuditRecorderPort NOOP = new ChatAuditRecorderPort() {
        @Override
        public @NonNull Session begin(@NonNull BeginContext context) {
            return Session.NOOP;
        }

        @Override
        public void recordRejected(@NonNull ChatAuditRejected record,
                                   @NonNull ChatAuditConfig config) {
            // 关闭态：连「被拒请求」也不记。
        }
    };

    /**
     * 记录一条「建连前即被拒」的请求（只存元数据，无原文）。
     *
     * <p>调用点是 HTTP 过滤器：此时身份未成立、租户未知，故走独立的未归属表。
     * 同样 fail-open——记录失败不影响响应。
     */
    void recordRejected(@NonNull ChatAuditRejected record, @NonNull ChatAuditConfig config);

    /** 单次请求录制会话。 */
    interface Session {
        /**
         * 补写身份（请求线程在安全链之后填、或编排器从已验签上下文填）。
         *
         * <p>⚠️ 为什么身份是「后补」而不是入口就定：审计过滤器注册在安全链<b>之前</b>
         * （否则未认证就失败的请求根本进不来），此刻还拿不到可信身份。
         * 平台主体的目标租户甚至只在请求体里（由身份解析器校验后才可信）。
         *
         * <p>可多次调用：<b>后写覆盖</b>，以最接近业务事实的来源为准。
         */
        void identify(@NonNull String domain,
                      @Nullable String tenantId,
                      @Nullable String principalId);

        /** 记录结构化请求事实（message / history / agentId / topK / 场景）；后者覆盖。 */
        void recordRequest(@Nullable RequestInfo request, boolean bodyTruncated);

        /**
         * 记录 HTTP 原始 body（脱敏与截断由实现负责）。
         *
         * <p>⚠️ 与 {@link #recordRequest} 分开是<b>时序决定的</b>：body 只有在控制器
         * 读过之后才拿得到（Servlet 的请求流是一次性的），而结构化事实在编排入口就有。
         * 两者合并成一个方法必然让先到的一方把后到的一方覆盖掉。
         */
        void setRawRequestBody(@Nullable String rawBody, boolean truncated);

        /**
         * 记录本轮送模型的 system prompt 与消息序列（模型调用入口回调）。
         *
         * <p>⚠️ 可能多轮：后者覆盖前者——V1 只保留最后一次送模型的消息，
         * 完整的多轮轨迹由 {@link #recordChunk} 与事件帧还原。
         */
        void recordModelMessages(@Nullable String systemPrompt, @NonNull List<Turn> messages);

        /** 记录一个 SSE 事件帧（seq 由实现按到达顺序分配）。 */
        void recordFrame(@NonNull String name, @Nullable String data);

        /** 记录一个逐分片（delta / thinking）。 */
        void recordChunk(@NonNull String kind, @NonNull String text);

        /** 设置最终结果（完整回答 + 统计）；可多次调用，后者覆盖。 */
        void setResult(@Nullable Result result);

        /** 设置 HTTP 状态码（进入时未知，结束时补）。 */
        void setHttpStatus(int status);

        /** 标记回答因超长被截断。 */
        void markAnswerTruncated();

        /** 收敛终态并提交写入。幂等：多次调用只生效一次。 */
        void finish(@NonNull String terminalState, @Nullable ErrorInfo error);

        /** 关闭态单例会话（所有方法 no-op）。 */
        Session NOOP = new Session() {
            @Override public void identify(@NonNull String d, @Nullable String t,
                                           @Nullable String p) {}
            @Override public void recordRequest(@Nullable RequestInfo r, boolean t) {}
            @Override public void setRawRequestBody(@Nullable String b, boolean t) {}
            @Override public void recordModelMessages(@Nullable String sp, @NonNull List<Turn> m) {}
            @Override public void recordFrame(@NonNull String n, @Nullable String d) {}
            @Override public void recordChunk(@NonNull String k, @NonNull String x) {}
            @Override public void setResult(@Nullable Result r) {}
            @Override public void setHttpStatus(int s) {}
            @Override public void markAnswerTruncated() {}
            @Override public void finish(@NonNull String s, @Nullable ErrorInfo e) {}
        };
    }

    /** 请求入口快照（请求线程构造，带进虚拟线程；不可变）。 */
    record BeginContext(
            @NonNull String auditId,
            @NonNull String traceId,
            @NonNull String endpoint,
            @NonNull String domain,
            @Nullable String tenantId,
            @Nullable String principalId,
            @NonNull Instant startedAt,
            @NonNull String httpMethod,
            @NonNull String requestUri,
            @NonNull Map<String, String> headers,
            @NonNull ChatAuditConfig config,
            long configVersion) {
    }
}
