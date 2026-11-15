package cn.studykid.multiagent.api.error;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 异常日志里的<b>请求上下文</b>：接口地址 + 参数。
 *
 * <p><b>为什么要有它</b>：异常日志若只有「异常类型 + 业务键」，排障的人第一件事
 * 是去翻前端代码猜「这次请求到底带了什么」——接口地址、路径参数、查询参数
 * 都是排障的第一条线索，缺一条就要多绕一圈。
 *
 * <p><b>与访问日志的分工（口径不同是刻意的）</b>：
 * {@code RequestLoggingFilter} 打的访问日志<b>刻意只打 path、不打 query</b>
 * （query 可能含令牌/授权码，落日志即泄露）；而异常路径是<b>低频取证</b>，
 * 参数是刚需。本类因此<b>带参数，但一律脱敏后再带</b>。
 *
 * <p><b>三条不可退让的纪律</b>：
 * <ol>
 *   <li><b>敏感键值必须掩码</b>：口令 / 令牌 / 密钥 / 凭据 / 会话 / 验证码，
 *       以及形如 JWT、Bearer 的值——「记下参数」不等于「把凭据抄进日志」。
 *       日志一旦进了集中式日志系统，权限边界与数据库完全不同。</li>
 *   <li><b>值里的控制字符必须净化</b>：参数是攻击者可控输入，未净化即
 *       <b>日志注入</b>——一个 {@code \n} 就能伪造出一整行「正常」日志，
 *       把排障结论带偏。</li>
 *   <li><b>长度与条数必须有上限</b>：一个超长 prompt 或上百个参数会把日志冲垮，
 *       同时把「不存原文」的纪律撑破。</li>
 * </ol>
 *
 * <p><b>JSON 请求打 body，其余请求打参数</b>：JSON 请求的载荷全在 body 里，
 * 只打 query 参数等于什么都没打——排障时最需要的恰恰是「这次到底传了什么」。
 * body 由 {@link RequestBodyCachingConfiguration} 的过滤器在入口处缓存
 * （有界：只留前 8 KiB），因此异常到达时仍可回读。
 * 路径参数已体现在 URI 里，不重复打印。
 *
 * <p><b>打了 body 不等于放弃纪律</b>：body 里的口令 / 令牌 / 密钥一类敏感键、
 * 以及 JWT / Bearer 形态的值，一律掩码；控制字符净化、长度有上限。
 * 也就是说，「记下这次请求」仍然不等于「把凭据抄进日志」。
 *
 * <p><b>无请求上下文时返回 {@code -}</b>：不是空串——「没有上下文」与
 * 「上下文是空串」在日志里长得一样，而后者往往意味着上游塞了空租户，
 * 那是必须看得见的异常。
 */
final class RequestLogContext {

    /** 无请求上下文时的占位值（与 {@code LogContext.NONE} 同义：未知，不是空）。 */
    static final String NONE = "-";

    /** 敏感值的掩码字样。 */
    private static final String MASK = "***";

    /** URI 最长保留长度：路径天然可能较长（层级 + 资源名），截太短会丢掉定位信息。 */
    private static final int MAX_URI_LENGTH = 256;

    /** 单个参数值最长保留长度。 */
    private static final int MAX_VALUE_LENGTH = 64;

    /** 单个参数名最长保留长度。 */
    private static final int MAX_KEY_LENGTH = 48;

    /** 最多记录多少个参数（超出只记条数，不记内容）。 */
    private static final int MAX_PARAMS = 12;

    /** JSON body 整体最长保留长度：一条日志不能被一次请求冲垮。 */
    private static final int MAX_BODY_LENGTH = 1024;

    /** JSON body 里单个字符串值最长保留长度。 */
    private static final int MAX_BODY_VALUE_LENGTH = 128;

    /**
     * JSON 解析器。
     *
     * <p><b>只读用</b>：本类只做 {@code readTree} 与写出，从不改配置，
     * 因此一个静态实例即可（ObjectMapper 读操作是线程安全的）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 解析失败时的兜底掩码正则：{@code "键": 值} 对。
     *
     * <p><b>为什么要有兜底</b>：body 解析失败恰恰是<b>最需要看原文</b>的时候
     *（{@code HttpMessageNotReadableException} 就是这种场景）。
     * 但原文里仍可能混着口令，所以不能直接打——按键名逐个掩码后再打。
     *
     * <p>值同时匹配字符串与数字 / 布尔 / null：{@code "otp": 123456}
     * 里的验证码是数字，只匹字符串会漏掉它。
     */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "(\"(?:\\\\.|[^\"\\\\])*\")\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|[^,}\\]\\s]+)");

    /**
     * 敏感键判定：把键名归一化（小写 + 去掉 {@code - _ . }）后是否包含下列片段。
     *
     * <p><b>为什么是片段而非精确匹配</b>：真实世界的键名有
     * {@code password / pwd / passwd / userPassword / client_secret /
     * api_key / x-api-key / access_token / refreshToken / idToken} 等几十种变体，
     * 精确名单迟早漏一个；归一化后的片段匹配覆盖住这一族。
     *
     * <p><b>为什么不含裸 {@code auth}</b>：{@code author / authority} 一类正常业务键
     * 会被误伤，而真正需要掩码的是 {@code authorization / credential}，已单列。
     */
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "apikey",
            "authorization", "credential", "privatekey", "signature",
            "jwt", "sessionid", "cookie", "otp", "captcha", "salt");

    /**
     * JWT 形态：base64url 头（必以 {@code eyJ} 开头）+ 两段点分。
     *
     * <p>用于「键名无害但值是凭据」的情形（如 {@code ?assertion=eyJ...}）。
     */
    private static final Pattern JWT_LIKE = Pattern.compile(
            "^eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]*$");

    private RequestLogContext() {
    }

    /**
     * 当前请求的上下文快照；没有绑定请求时返回 {@link #NONE}。
     *
     * <p><b>为什么用 {@code RequestContextHolder} 而不是让每个处理器注入
     * {@code HttpServletRequest}</b>：注入参数会让本类的每个处理方法签名都变，
     * 而它们是<b>被直接单测调用</b>的（{@code ApiExceptionHandlerTest}），
     * 签名一变就等于把「无请求上下文」这条路径从测试里删掉。
     */
    static String describe() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return describe(servletAttributes.getRequest());
        }
        return NONE;
    }

    /**
     * 请求上下文快照：{@code method=… uri=… [pattern=…] body={…}}（JSON 请求）
     * 或 {@code method=… uri=… [pattern=…] params={…}}（其余请求）。
     *
     * <p>{@code pattern} 是命中的<b>路由模板</b>（如 {@code /api/agents/{agentId}}），
     * 由 {@code DispatcherServlet} 挂在请求属性上。它与 {@code uri} 互补：
     * URI 里有真实资源 id，模板里有端点形状——两者都在，一眼就知道是哪个端点出的问题。
     *
     * <p><b>为什么 JSON 请求打 body、其余打参数</b>：JSON 请求的载荷全在 body 里，
     * 只打 query 参数等于什么都没打。两者不冲突：JSON 请求若同时带 query 参数，
     * 参数照样附在后面（{@code body={…} params={…}}）。
     */
    static String describe(@Nullable HttpServletRequest request) {
        if (request == null) {
            return NONE;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("method=").append(plain(request.getMethod()));
        sb.append(" uri=").append(sanitize(request.getRequestURI(), MAX_URI_LENGTH));
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String matched && !matched.isBlank()) {
            sb.append(" pattern=").append(sanitize(matched, MAX_URI_LENGTH));
        }
        String body = jsonBodyOf(request);
        if (body != null) {
            sb.append(" body=").append(body);
            String params = paramsOf(request);
            if (!params.equals("{}")) {
                sb.append(" params=").append(params);
            }
        } else {
            sb.append(" params=").append(paramsOf(request));
        }
        return sb.toString();
    }

    /**
     * JSON 请求的 body 快照；<b>非 JSON 请求返回 {@code null}</b>（由调用方决定打参数）。
     *
     * <p><b>三种结果</b>：
     * <ul>
     *   <li>{@code {…}}：body 读到了，按键掩码后打成 JSON；</li>
     *   <li>{@code {}}：body 读过但确实是空的；</li>
     *   <li>{@code {unavailable}}：<b>拿不到</b>——请求没被过滤器包装，
     *       或 body 在异常发生前压根没被读过（此时打「空」会误导排障：
     *       「客户端没传」与「我们没读到」是两件事）。</li>
     * </ul>
     */
    @Nullable
    private static String jsonBodyOf(HttpServletRequest request) {
        if (!isJsonRequest(request)) {
            return null;
        }
        if (!(request instanceof CachedBodyHttpServletRequest cached)) {
            return "{unavailable}";
        }
        byte[] body = cached.cachedBody();
        if (body.length == 0) {
            return cached.isBodyProvided() ? "{}" : "{unavailable}";
        }
        String raw = new String(body, charsetOf(request));
        return sanitize(renderJson(raw), MAX_BODY_LENGTH);
    }

    /** Content-Type 是否为 JSON（含 {@code application/vnd.api+json} 这类后缀形式）。 */
    private static boolean isJsonRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("application/json") || normalized.contains("+json");
    }

    /**
     * body 渲染：<b>能解析就按 JSON 结构掩码后输出，解析不了就按键名兜底掩码后输出原文</b>。
     *
     * <p><b>为什么解析失败也要打原文</b>：body 格式错误本身就是一类高频故障
     * （{@code HttpMessageNotReadableException}），此时「原文长什么样」正是排障的第一现场。
     * 但原文里可能混着口令，所以走按键名的兜底掩码，而不是原样打印。
     */
    private static String renderJson(String raw) {
        try {
            JsonNode tree = MAPPER.readTree(raw);
            if (tree == null || tree.isMissingNode()) {
                return maskRawJson(raw);
            }
            return MAPPER.writeValueAsString(mask(tree));
        } catch (Exception e) {
            // ⚠️ 日志辅助代码绝不能把原异常顶掉：解析失败就降级，不抛。
            return maskRawJson(raw);
        }
    }

    /**
     * 逐字段掩码：敏感键与「键名无害但值像凭据」的值一律换成 {@code ***}。
     *
     * <p><b>为什么在结构里掩而不是在字符串里搜</b>：字符串搜索会把
     * 「恰好包含 password 这个词的普通文本」也抹掉（比如一段讲解口令机制的提示词），
     * 抹得不准等于把线索也一起抹了。
     */
    private static JsonNode mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode masked = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isSensitiveKey(key) || (value.isTextual() && looksLikeCredential(value.asText()))) {
                    masked.put(key, MASK);
                } else {
                    masked.set(key, mask(value));
                }
            }
            return masked;
        }
        if (node.isArray()) {
            ArrayNode masked = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                masked.add(mask(item));
            }
            return masked;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (looksLikeCredential(text)) {
                return StringNode.valueOf(MASK);
            }
            return StringNode.valueOf(sanitize(text, MAX_BODY_VALUE_LENGTH));
        }
        return node;
    }

    /**
     * 解析失败时的兜底：按 {@code "键": 值} 逐对掩码，其余原样保留。
     *
     * <p>掩码判据与结构化路径完全一致（敏感键名 / 值像凭据），
     * 因此「能解析」与「不能解析」两条路径的<b>掩码口径不会分叉</b>。
     */
    private static String maskRawJson(String raw) {
        Matcher matcher = JSON_PAIR.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        while (matcher.find()) {
            String key = unquote(matcher.group(1));
            String value = unquote(matcher.group(2));
            String replacement = matcher.group(0);
            if (isSensitiveKey(key) || looksLikeCredential(value)) {
                replacement = matcher.group(1) + ":\"" + MASK + "\"";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 去掉 JSON 字符串字面量两侧的引号。 */
    private static String unquote(String quoted) {
        if (quoted == null || quoted.length() < 2) {
            return "";
        }
        return quoted.substring(1, quoted.length() - 1);
    }

    /** 请求字符集：取不到或非法时回落 UTF-8（JSON 的默认字符集）。 */
    private static Charset charsetOf(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * query / form 参数快照。
     *
     * <p><b>排序后再拼</b>：{@code getParameterMap()} 的迭代顺序不保证稳定，
     * 不排序的话同一请求两次失败的日志长得不一样，排障时没法逐字比对。
     */
    private static String paramsOf(HttpServletRequest request) {
        if (isMultipart(request)) {
            // ⚠️ multipart 的参数解析会牵动文件解析（可能落盘 / 占内存），
            //    异常路径上不碰：为了多打几个字符去动文件流，收益与风险完全不成比例。
            return "{}";
        }
        Map<String, String[]> parameters;
        try {
            parameters = request.getParameterMap();
        } catch (RuntimeException e) {
            // ⚠️ 参数解析本身失败 → 只记占位符。
            //    日志辅助代码绝不能把原异常顶掉（那会让排障从「有线索」变成「没线索」）。
            return "{unavailable}";
        }
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }
        Map<String, String[]> sorted = new TreeMap<>();
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            if (entry.getKey() != null) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        StringBuilder sb = new StringBuilder("{");
        int shown = 0;
        for (Map.Entry<String, String[]> entry : sorted.entrySet()) {
            if (shown >= MAX_PARAMS) {
                sb.append("...(+").append(sorted.size() - shown).append(")");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append(sanitize(entry.getKey(), MAX_KEY_LENGTH))
                    .append('=')
                    .append(valuesOf(entry.getKey(), entry.getValue()));
            shown++;
        }
        return sb.append('}').toString();
    }

    /** 同一个键可能有多个值（{@code ?a=1&a=2}），逐个脱敏后用逗号连接。 */
    private static String valuesOf(String key, @Nullable String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        boolean sensitive = isSensitiveKey(key);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String value = values[i];
            sb.append(sensitive || looksLikeCredential(value)
                    ? MASK
                    : sanitize(value, MAX_VALUE_LENGTH));
        }
        return sb.toString();
    }

    /** 键名归一化后是否命中敏感片段。 */
    private static boolean isSensitiveKey(@Nullable String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 值本身像凭据（Bearer / JWT）时同样掩码——键名无害不代表值无害。 */
    private static boolean looksLikeCredential(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return true;
        }
        return JWT_LIKE.matcher(trimmed).matches();
    }

    /**
     * 净化：控制字符替换为空格 + 超长截断。
     *
     * <p><b>控制字符必须处理</b>：参数值原样进日志即<b>日志注入</b>——
     * 攻击者传 {@code name=a%0A2026-01-01 WARN 管理员已授权} 就能伪造日志行。
     */
    private static String sanitize(@Nullable String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length(); i++) {
            if (sb.length() >= maxLength) {
                sb.append("...");
                break;
            }
            char c = raw.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? ' ' : c);
        }
        return sb.toString();
    }

    /** 短文本（如 HTTP 方法）的净化结果；空值给占位符而不是空串。 */
    private static String plain(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return NONE;
        }
        return sanitize(raw, 16);
    }

    /** 是否 multipart 请求。 */
    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }
}
