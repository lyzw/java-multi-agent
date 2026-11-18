package cn.studykid.multiagent.application.agentchataudit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;

/**
 * 对话审计配置校验器（写入期 fail-closed）。
 *
 * <p><b>⚠️ 校验发生在写库之前</b>：被拒的配置不落库、不写审计——
 * 与 {@code ConfigTighteningPolicy} 同纪律。前端整表提交，缺字段由
 * {@link #normalize(ChatAuditConfig)} 用 {@link ChatAuditConfig#defaults()} 补齐后校验。
 *
 * <p><b>⚠️ 安全不变量不可配</b>：
 * <ul>
 *   <li>{@code writer.onSinkError} 只允许 IGNORE / LOG_ONLY——杜绝"审计失败中断业务"；</li>
 *   <li>目录必须绝对路径、禁止 {@code ..} 穿越；</li>
 *   <li>强制脱敏 Header 黑名单见 {@link ChatAuditConfig#FORCED_REDACTED_HEADERS}，不在此配置。</li>
 * </ul>
 */
public final class ChatAuditConfigValidator {

    private ChatAuditConfigValidator() {
    }

    /**
     * 归一化：逐层补齐 null / 缺省字段，返回可安全校验与持久化的完整配置。
     *
     * <p> Jackson 反序列化时若前端漏传整个嵌套对象，会出现 null 子记录；
     * 这里逐层回落默认值，绝不把 null 带进运行时导致 NPE。
     */
    public static @NonNull ChatAuditConfig normalize(@Nullable ChatAuditConfig raw) {
        ChatAuditConfig d = ChatAuditConfig.defaults();
        if (raw == null) {
            return d;
        }
        ChatAuditConfig.Scope ds = d.scope();
        ChatAuditConfig.Scope scope = raw.scope() == null ? ds
                : new ChatAuditConfig.Scope(
                        raw.scope().endpoints() == null ? ds.endpoints() : raw.scope().endpoints(),
                        raw.scope().includePreconnectionFailures(),
                        raw.scope().includeRoutingModelCalls());
        ChatAuditConfig.Sinks.Database ddb = d.sinks().database();
        ChatAuditConfig.Sinks.Database db = raw.sinks() == null || raw.sinks().database() == null
                ? ddb : new ChatAuditConfig.Sinks.Database(raw.sinks().database().enabled());
        ChatAuditConfig.Sinks.FileSink dfs = d.sinks().file();
        ChatAuditConfig.Sinks.FileSink file = raw.sinks() == null || raw.sinks().file() == null
                ? dfs : new ChatAuditConfig.Sinks.FileSink(
                        raw.sinks().file().enabled(),
                        raw.sinks().file().directory() == null ? dfs.directory() : raw.sinks().file().directory(),
                        raw.sinks().file().partition() == null ? dfs.partition() : raw.sinks().file().partition(),
                        raw.sinks().file().fsync());
        ChatAuditConfig.Sinks sinks = new ChatAuditConfig.Sinks(db, file);
        ChatAuditConfig.Capture dc = d.capture();
        ChatAuditConfig.Capture capture = raw.capture() == null ? dc
                : new ChatAuditConfig.Capture(
                        raw.capture().rawRequestBody(), raw.capture().rawAnswer(),
                        raw.capture().thinking(), raw.capture().deltaChunks(),
                        raw.capture().modelRequestMessages(), raw.capture().ragHits(),
                        raw.capture().toolArgsAndResults(),
                        raw.capture().headers() == null ? dc.headers() : raw.capture().headers());
        ChatAuditConfig.Redaction dr = d.redaction();
        ChatAuditConfig.Redaction redaction = raw.redaction() == null ? dr
                : new ChatAuditConfig.Redaction(
                        raw.redaction().mask() == null ? dr.mask() : raw.redaction().mask(),
                        raw.redaction().sensitiveFields() == null
                                ? dr.sensitiveFields() : raw.redaction().sensitiveFields());
        ChatAuditConfig.Sampling sampling = raw.sampling() == null ? d.sampling() : raw.sampling();
        ChatAuditConfig.Writer dw = d.writer();
        ChatAuditConfig.Writer writer = raw.writer() == null ? dw
                : new ChatAuditConfig.Writer(
                        raw.writer().async(), raw.writer().queueCapacity(),
                        raw.writer().onSinkError() == null ? dw.onSinkError() : raw.writer().onSinkError(),
                        raw.writer().maxChunksPerRequest());
        ChatAuditConfig.Retention retention = raw.retention() == null ? d.retention() : raw.retention();
        ChatAuditConfig.Limits limits = raw.limits() == null ? d.limits() : raw.limits();
        return new ChatAuditConfig(raw.enabled(), scope, sinks, capture, redaction,
                sampling, writer, retention, limits);
    }

    /**
     * 校验归一化后的配置；有问题抛 {@link ChatAuditConfigInvalidException}（→ 400）。
     */
    public static void validate(@NonNull ChatAuditConfig c) {
        List<String> problems = new ArrayList<>();

        double rate = c.sampling().rate();
        if (Double.isNaN(rate) || rate < 0.0d || rate > 1.0d) {
            problems.add("sampling.rate 必须在 [0,1] 之间");
        }
        int days = c.retention().days();
        if (days < 1 || days > 180) {
            problems.add("retention.days 必须在 [1,180] 之间");
        }
        int queue = c.writer().queueCapacity();
        if (queue < 1 || queue > 100_000) {
            problems.add("writer.queueCapacity 必须在 [1,100000] 之间");
        }
        int maxChunks = c.writer().maxChunksPerRequest();
        if (maxChunks < 1 || maxChunks > 1_000_000) {
            problems.add("writer.maxChunksPerRequest 必须在 [1,1000000] 之间");
        }
        String onError = c.writer().onSinkError();
        if (!ChatAuditConfig.Writer.IGNORE.equals(onError)
                && !ChatAuditConfig.Writer.LOG_ONLY.equals(onError)) {
            problems.add("writer.onSinkError 只允许 IGNORE / LOG_ONLY（不允许中断业务）");
        }
        String partition = c.sinks().file().partition();
        if (!"day".equals(partition) && !"hour".equals(partition)) {
            problems.add("sinks.file.partition 只允许 day / hour");
        }
        if (c.sinks().file().enabled()) {
            String problem = directoryProblem(c.sinks().file().directory());
            if (problem != null) {
                problems.add("sinks.file.directory " + problem);
            }
        }
        if (c.limits().maxBodyBytes() < 1) {
            problems.add("limits.maxBodyBytes 必须 > 0");
        }
        if (c.limits().maxAnswerChars() < 1) {
            problems.add("limits.maxAnswerChars 必须 > 0");
        }
        if (c.redaction().mask() == null || c.redaction().mask().isEmpty()) {
            problems.add("redaction.mask 不可为空");
        }
        Set<String> requested = c.scope().endpoints() == null
                ? Set.of() : new LinkedHashSet<>(c.scope().endpoints());
        for (String endpoint : requested) {
            if (!ChatAuditConfig.ALLOWED_ENDPOINTS.contains(endpoint)) {
                problems.add("scope.endpoints 含非法端点: " + endpoint);
            }
        }

        if (!problems.isEmpty()) {
            throw new ChatAuditConfigInvalidException(String.join("；", problems));
        }
    }

    /**
     * 目录路径校验：必须绝对、禁止 {@code ..} 穿越、禁止文件系统根。
     *
     * @return 问题描述；null = 合法
     */
    static @Nullable String directoryProblem(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "不可为空";
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (RuntimeException e) {
            return "不是合法路径";
        }
        if (!path.isAbsolute()) {
            return "必须为绝对路径";
        }
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return "禁止包含 .. 穿越段";
            }
        }
        if (path.getParent() == null || path.getNameCount() == 0) {
            return "不可为文件系统根";
        }
        return null;
    }
}
