package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 平台级对话审计插件配置（单例，前端「系统配置 → 对话审计配置」维护）。
 *
 * <p><b>⚠️ 本类型是纯数据契约</b>，放在 ports 层，不依赖 Jackson / Spring。
 * 反序列化由 {@code agent-persistence} / {@code agent-api} 的 Jackson 3 完成
 * （Java record 组件名由 class 文件原生携带，无需额外注解）。
 *
 * <p><b>⚠️ 默认值来源唯一</b>：{@link #defaults()} 是 yml/DB 缺省时唯一的安全基线。
 * 7 项已确认决策（含原文、双写、逐分片、全量、fail-open、前端可配）全部体现在默认值里；
 * 前端改的是覆盖值，缺字段由 {@link #defaults()} 补齐（fail-safe）。
 *
 * <p><b>⚠️ 强制脱敏黑名单不在本配置里</b>：见 {@link #FORCED_REDACTED_HEADERS}。
 * 该常量是安全不变量，前端无法关闭——把凭据写进审计会影响租户隔离与 PIPL。
 */
public record ChatAuditConfig(
        boolean enabled,
        @NonNull Scope scope,
        @NonNull Sinks sinks,
        @NonNull Capture capture,
        @NonNull Redaction redaction,
        @NonNull Sampling sampling,
        @NonNull Writer writer,
        @NonNull Retention retention,
        @NonNull Limits limits) {

    /**
     * 允许记录的端点白名单（写入时按此校验，防止任意端点被塞进来）。
     *
     * <p><b>⚠️ {@code /api/v1/retrieval} 的边界</b>：该端点在 <b>rag 独立进程</b>
     * （{@code rag/rag-api}），本插件装配在 platform 进程，因此这一项在本进程内
     * <b>永远匹配不到请求</b>。保留它是为了两件事：① 接入 rag 模块时白名单已就位；
     * ② 配置页面能如实展示「设计上要审计 RAG」。
     * ⛔ 不要把「白名单里有 /api/v1/retrieval」当成「RAG 检索已被审计」的证据。
     */
    public static final List<String> ALLOWED_ENDPOINTS = List.of(
            "/api/agent/chat/stream",
            "/api/knowledge/agent/chat/stream",
            "/api/chat/stream",
            "/api/knowledge/chat/stream",
            "/api/runs",
            "/api/v1/retrieval");

    /**
     * 强制脱敏 Header 黑名单（无论前端如何配置都替换成掩码）。
     *
     * <p>⚠️ 不可覆盖的安全不变量：Bearer / API Key / Cookie 一旦落库落盘，
     * 就等于把租户凭据复制进了审计通道——这是 P0.5 红线的延伸。
     */
    public static final List<String> FORCED_REDACTED_HEADERS = List.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "x-platform-token", "proxy-authorization");

    /** 采集范围。 */
    public record Scope(
            @NonNull List<String> endpoints,
            boolean includePreconnectionFailures,
            boolean includeRoutingModelCalls) {
        public static Scope defaults() {
            return new Scope(ALLOWED_ENDPOINTS, true, true);
        }
    }

    /** 落盘双写开关（DB + 目录各自独立）。 */
    public record Sinks(@NonNull Database database, @NonNull FileSink file) {
        public record Database(boolean enabled) {
            public static Database defaults() {
                return new Database(true);
            }
        }

        public record FileSink(
                boolean enabled,
                @NonNull String directory,
                @NonNull String partition,
                boolean fsync) {
            public static FileSink defaults() {
                return new FileSink(true, "/data/agent/chat-audit", "day", false);
            }
        }

        public static Sinks defaults() {
            return new Sinks(Database.defaults(), FileSink.defaults());
        }
    }

    /** 采集内容开关（逐分片 / 原文 / thinking 全部默认开，对应已确认决策）。 */
    public record Capture(
            boolean rawRequestBody,
            boolean rawAnswer,
            boolean thinking,
            boolean deltaChunks,
            boolean modelRequestMessages,
            boolean ragHits,
            boolean toolArgsAndResults,
            @NonNull List<String> headers) {
        public static Capture defaults() {
            return new Capture(true, true, true, true, true, true, true, List.of());
        }
    }

    /** 脱敏（掩码 + 递归字段名）。 */
    public record Redaction(@NonNull String mask, @NonNull List<String> sensitiveFields) {
        public static Redaction defaults() {
            return new Redaction("***", List.of("password", "token", "secret", "apiKey",
                    "phone", "idCard", "studentName"));
        }
    }

    /** 采样（1.0 = 全量，默认）。 */
    public record Sampling(double rate) {
        public static Sampling defaults() {
            return new Sampling(1.0d);
        }
    }

    /** 异步写入器（fail-open 纪律：onSinkError 只允许 IGNORE / LOG_ONLY）。 */
    public record Writer(
            boolean async,
            int queueCapacity,
            @NonNull String onSinkError,
            int maxChunksPerRequest) {
        public static final String IGNORE = "IGNORE";
        public static final String LOG_ONLY = "LOG_ONLY";

        public static Writer defaults() {
            return new Writer(true, 10000, IGNORE, 20000);
        }
    }

    /** 保留期（与 message-days 对齐，PIPL）。 */
    public record Retention(int days) {
        public static Retention defaults() {
            return new Retention(30);
        }
    }

    /** 体积上限（超限截断并打标记）。 */
    public record Limits(int maxBodyBytes, int maxAnswerChars) {
        public static Limits defaults() {
            return new Limits(65536, 200000);
        }
    }

    /** 全量安全默认配置（唯一缺省基线）。 */
    public static @NonNull ChatAuditConfig defaults() {
        return new ChatAuditConfig(true, Scope.defaults(), Sinks.defaults(), Capture.defaults(),
                Redaction.defaults(), Sampling.defaults(), Writer.defaults(),
                Retention.defaults(), Limits.defaults());
    }

    /** 配置文件版本号（随每次保存递增，写入审计记录保证可复现）。 */
    public record State(
            @NonNull ChatAuditConfig config,
            long version,
            @Nullable String updatedBy,
            @Nullable Instant updatedAt) {
    }
}
