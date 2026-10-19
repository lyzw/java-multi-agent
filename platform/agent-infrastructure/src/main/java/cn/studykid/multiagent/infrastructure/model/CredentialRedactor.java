package cn.studykid.multiagent.infrastructure.model;

import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 凭据脱敏（P1-07 / AT-MODEL-002）。
 *
 * <p><b>为什么需要专门一层</b>：模型调用必然携带 API Key。
 * 而日志、异常消息、Trace 属性都可能意外把请求头或密钥带出去。
 * 供应商 SDK 抛出的异常里常常<b>原样包含</b>请求内容与头部——
 * 直接把 {@code e.getMessage()} 往上抛，等于把密钥写进日志。
 *
 * <p><b>本类的使用约定</b>：任何来自供应商 SDK 的字符串
 * （异常消息、响应体、请求头回显）在进入日志或异常前<b>必须</b>经此脱敏。
 */
public final class CredentialRedactor {

    /** 匹配常见密钥形态：sk-xxx、ghp_xxx、Bearer xxx 等。 */
    private static final Pattern API_KEY_PATTERNS = Pattern.compile(
            "(?i)\\b(sk-[a-z0-9\\-]{8,}|gh[pousr]_[a-z0-9]{16,}|"
                    + "AKIA[0-9A-Z]{12,}|xox[baprs]-[a-z0-9\\-]{8,})");

    /** 匹配 Authorization / api-key / token 等键值对。 */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(?i)\\b(authorization|api[-_]?key|secret|token|password)\\b\\s*[:=]\\s*"
                    + "(\"?)([^\\s\",;}]+)\\2");

    /** 脱敏后的占位符。刻意保留前缀长度信息，便于排查「用了哪个 key」。 */
    private static final String MASK = "***REDACTED***";

    private CredentialRedactor() {
    }

    /**
     * 脱敏任意文本。
     *
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public static @Nullable String redact(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = API_KEY_PATTERNS.matcher(text).replaceAll(MASK);
        // 键值对：保留键名（便于定位），只替换值
        result = HEADER_PATTERN.matcher(result).replaceAll(mr -> mr.group(1) + "=" + MASK);
        return result;
    }

    /**
     * 脱敏异常链的完整消息。
     *
     * <p><b>只取 message 不取 stackTrace 文本</b>——供应商异常的
     * {@code toString()} 有时包含完整请求体，风险更高。
     *
     * @return 归一化后可安全记录的消息
     */
    public static @NonNull String redactMessage(@NonNull Throwable error) {
        String msg = error.getMessage();
        return msg == null ? error.getClass().getSimpleName() : redact(msg);
    }

    /**
     * 生成异常消息，<b>不携带原始异常</b>。
     *
     * <p>⚠️ 刻意不传入 {@code cause}：把供应商异常挂在 cause 上，
     * 日志框架打印堆栈时会把原始消息一并输出，绕过本层脱敏。
     * 如需保留原始信息，请单独记录已脱敏的 {@link #redactMessage} 结果。
     */
    public static @NonNull String safeMessage(@NonNull String context, @Nullable Throwable error) {
        return error == null ? context : context + ": " + redactMessage(error);
    }
}