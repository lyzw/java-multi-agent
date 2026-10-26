package cn.studykid.multiagent.infrastructure.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.McpTrustTier;
import cn.studykid.multiagent.infrastructure.mcp.McpEndpointGuard;
import cn.studykid.multiagent.ports.http.HttpToolException;
import cn.studykid.multiagent.ports.mcp.McpTransportException;

/**
 * 远程 HTTP 工具的出站执行器（第三十七批 · 设计文档 §13.1「安全与外部 HTTP 工具」）。
 *
 * <p>⚠️ <b>为什么必须自建 HTTP 客户端，而不是用 {@code java.net.http.HttpClient}</b>：
 * {@code HttpClient} 按<b>域名</b>发起连接 —— 即「校验过的 IP」与「实际连上的 IP」
 * 可能是两个东西。而 §13.1 要求「<b>按解析后的实际 IP 判定</b>」：
 * 域名再正常，解析出来可能落在 {@code 169.254.169.254}（云元数据）。
 * 故本类直接建连到<b>已校验的 IP</b>，把原始主机名放进 {@code Host} 头；
 * HTTPS 场景用 {@code SSLSocketFactory.createSocket(socket, host, port, true)}，
 * 让 JDK 依旧按<b>主机名</b>做证书校验（SNI + endpoint identification），
 * 既钉死了目标 IP，又没有牺牲 TLS 的主机名校验。
 * （与 {@code HttpMcpTransport} 同款纪律 —— 两处必须同口径。）
 *
 * <p>⚠️ <b>信任级别恒为 {@link McpTrustTier#PUBLIC}</b>：
 * HTTP 工具没有「显式授权内网」的概念，因此内网 / 回环 / 链路本地地址
 * <b>一律禁止</b>（只有 {@code INTERNAL} 才会放行内网，而 HTTP 工具拿不到这个级别）。
 * 云元数据地址在任何级别下都<b>绝对禁止</b>（{@code McpAddressPolicy} 的既有语义）。
 *
 * <p>⚠️ <b>每次重定向都重新校验</b>（§13.1）：
 * 重定向目标由<b>远端</b>给出 —— 「先给一个合法地址，再 302 到内网」是
 * 最常见的 SSRF 绕过手法。故循环的每一次迭代都重新走一遍
 * {@link McpEndpointGuard#validate}，而不是只在第一次校验。
 *
 * <p>⚠️ <b>响应一律视为不可信</b>：响应体由 {@code HttpUntrustedOutput} 加边界与限体积。
 */
public class HttpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpToolExecutor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** URL 里的参数占位符：{@code {name}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final McpEndpointGuard guard;
    private final HttpToolOptions options;

    public HttpToolExecutor(@NonNull McpEndpointGuard guard, @NonNull HttpToolOptions options) {
        this.guard = guard;
        this.options = options;
    }

    /**
     * 执行一次远程 HTTP 请求。
     *
     * @param toolId     工具 id（用于日志与出站校验的来源标识）
     * @param config     HTTP 配置（方法 / URL / 超时）
     * @param arguments  模型给出的入参
     * @return 响应
     * @throws HttpToolException 执行失败
     */
    public @NonNull HttpToolResponse execute(@NonNull String toolId,
                                             @NonNull CapHttpToolConfig config,
                                             @NonNull Map<String, Object> arguments) {
        RequestPlan plan = plan(config, arguments);
        String url = plan.url();
        String method = plan.method();
        byte[] body = plan.body();

        for (int hop = 0; ; hop++) {
            URI uri = parseUri(url, toolId);
            // ⚠️ **每一跳都重新校验**（含第一次）：出站防线不能只做一次。
            McpEndpointGuard.@NonNull ValidatedEndpoint endpoint = validateTarget(uri, toolId);
            RawResponse raw = exchange(method, endpoint, requestTarget(uri, endpoint),
                    body, config.timeoutMs());

            String location = raw.headers().get("location");
            if (!isRedirect(raw.status()) || location == null || location.isBlank()) {
                return new HttpToolResponse(raw.status(),
                        new String(raw.body(), StandardCharsets.UTF_8),
                        raw.headers().get("content-type"), url);
            }
            if (hop >= options.maxRedirects()) {
                throw HttpToolException.of(HttpToolException.Kind.TOO_MANY_REDIRECTS,
                        "HTTP 工具 " + toolId + " 的重定向跳数超过上限 "
                                + options.maxRedirects() + "（url=" + url + "）", null);
            }
            URI next;
            try {
                next = uri.resolve(location.trim());
            } catch (IllegalArgumentException e) {
                throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                        "HTTP 工具 " + toolId + " 的重定向目标非法: " + location, e);
            }
            if (redirectDowngrades(method, raw.status())) {
                method = "GET";
                body = null;
            }
            url = next.toString();
            log.debug("HTTP 工具跟随重定向: tool={}, status={}, next={}", toolId, raw.status(), url);
        }
    }

    /**
     * 出站校验（每一跳都走这里）。
     *
     * <p>⚠️ <b>把守卫的异常翻译成 HTTP 专用的类型</b>：
     * 守卫抛的是 {@code McpTransportException}（MCP 传输层的异常分类），
     * 而 HTTP 工具的失败语义要由 {@link HttpToolException} 表达 ——
     * 否则「被 SSRF 防线拦下」与「连接失败」在上层会退化成同一类，
     * 而这两者的处置完全不同（前者是安全事件，后者是可重试的网络抖动）。
     *
     * @param uri    待校验的目标
     * @param toolId 工具 id（仅用于日志）
     * @return 已校验的端点
     */
    private McpEndpointGuard.@NonNull ValidatedEndpoint validateTarget(@NonNull URI uri,
                                                                        @NonNull String toolId) {
        try {
            return guard.validate(uri.toString(), McpTrustTier.PUBLIC, toolId);
        } catch (McpTransportException e) {
            HttpToolException.Kind kind = switch (e.kind()) {
                case SSRF_BLOCKED -> HttpToolException.Kind.SSRF_BLOCKED;
                case CONNECT_FAILED -> HttpToolException.Kind.CONNECT_FAILED;
                case TIMEOUT -> HttpToolException.Kind.TIMEOUT;
                default -> HttpToolException.Kind.CONFIG_INVALID;
            };
            throw HttpToolException.of(kind,
                    "HTTP 工具 " + toolId + " 的目标未通过出站校验: " + e.getMessage(), e);
        }
    }

    // ── 请求规划（参数 → URL / 请求体）────────────────────────────
    /**
     * 一次请求的规划结果。
     *
     * @param method HTTP 方法
     * @param url    最终 URL
     * @param body   请求体（无请求体时为 null）
     */
    private record RequestPlan(@NonNull String method, @NonNull String url,
                               @Nullable byte[] body) {
    }

    /**
     * 把「工具配置的 URL + 模型给的参数」映射成一次具体的 HTTP 请求。
     *
     * <p>⚠️ <b>参数去向是确定的、可预期的</b>（不是「看情况」）：
     * <ol>
     *   <li><b>URL 占位符</b>（{@code {name}}）→ 替换成对应参数值（URL 编码）；</li>
     *   <li><b>剩余参数</b>：
     *       <ul>
     *         <li>方法带请求体（POST / PUT / PATCH）→ JSON 请求体；</li>
     *         <li>方法不带请求体（GET / HEAD / DELETE）→ 查询串。</li>
     *       </ul>
     *   </li>
     * </ol>
     * 每条参数都有归宿 —— 不会出现「参数被静默丢弃」，
     * 那会让调用方以为参数生效了。
     *
     * <p>⚠️ <b>占位符缺值即拒</b>：URL 里写着 {@code {id}} 却没有 {@code id} 参数，
     * 是一条**配置错误**，不是「留空继续」。放过它等于把 {@code {id}} 原样发出去。
     */
    private @NonNull RequestPlan plan(@NonNull CapHttpToolConfig config,
                                      @NonNull Map<String, Object> arguments) {
        String method = config.method();
        Map<String, Object> remaining = new LinkedHashMap<>(arguments);
        String url = config.url();

        Matcher matcher = PLACEHOLDER.matcher(url);
        StringBuffer substituted = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!remaining.containsKey(name)) {
                throw HttpToolException.of(HttpToolException.Kind.CONFIG_INVALID,
                        "HTTP 工具的 URL 占位符 {" + name + "} 没有对应的参数值"
                                + "（url=" + url + "）", null);
            }
            Object value = remaining.remove(name);
            matcher.appendReplacement(substituted,
                    Matcher.quoteReplacement(pathEncode(scalarText(value))));
        }
        matcher.appendTail(substituted);
        url = substituted.toString();

        byte[] body = null;
        if (config.hasRequestBody()) {
            ObjectNode node = MAPPER.createObjectNode();
            for (Map.Entry<String, Object> entry : remaining.entrySet()) {
                node.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
            }
            body = MAPPER.writeValueAsBytes(node);
        } else {
            String query = buildQuery(remaining);
            if (!query.isEmpty()) {
                url = url + (url.contains("?") ? "&" : "?") + query;
            }
        }
        return new RequestPlan(method, url, body);
    }

    private static @NonNull String buildQuery(@NonNull Map<String, Object> arguments) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = queryEncode(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                // ⚠️ 列表按**重复键**展开（{@code k=a&k=b}）—— 这是查询串的通用约定，
                //    而把列表拼成一个 {@code [a, b]} 字符串会让服务端解析出不同的东西。
                for (Object item : list) {
                    appendPair(query, key, queryEncode(scalarText(item)));
                }
            } else {
                appendPair(query, key, queryEncode(scalarText(value)));
            }
        }
        return query.toString();
    }

    private static void appendPair(@NonNull StringBuilder query,
                                   @NonNull String key,
                                   @NonNull String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(key).append('=').append(value);
    }

    /** 标量转文本；对象/数组转 JSON 文本（避免出现 Java 的 {@code {a=1}} 这种歧义写法）。 */
    private static @NonNull String scalarText(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return MAPPER.writeValueAsString(value);
    }

    /** 路径段编码（空格用 {@code %20}，不用 {@code +} —— 后者只在查询串里成立）。 */
    private static @NonNull String pathEncode(@NonNull String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static @NonNull String queryEncode(@NonNull String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    // ── 出站交换 ────────────────────────────────────────────────────

    /**
     * 原始 HTTP 响应。
     *
     * @param status  状态码
     * @param headers 响应头（小写键）
     * @param body    响应体字节
     */
    private record RawResponse(int status, @NonNull Map<String, String> headers,
                               @NonNull byte[] body) {
    }

    /**
     * 一次真实的 HTTP 交换：连到<b>已校验的 IP</b>，{@code Host} 头用原始主机名。
     */
    private @NonNull RawResponse exchange(@NonNull String method,
                                          McpEndpointGuard.@NonNull ValidatedEndpoint endpoint,
                                          @NonNull String requestTarget,
                                          @Nullable byte[] body,
                                          int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Socket socket = null;
        try {
            socket = new Socket();
            int connectTimeout = (int) Math.min(options.connectTimeoutMs(), timeoutMs);
            socket.connect(new InetSocketAddress(endpoint.primaryAddress(), endpoint.port()),
                    connectTimeout);

            Socket io = socket;
            if ("https".equals(endpoint.scheme())) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(
                        socket, endpoint.host(), endpoint.port(), true);
                SSLParameters params = ssl.getSSLParameters();
                // ⚠️ 主机名校验必须保留：连的是 IP，证书要对的是主机名
                params.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(endpoint.host())) {
                    params.setServerNames(List.of(new SNIHostName(endpoint.host())));
                }
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                io = ssl;
            }

            StringBuilder head = new StringBuilder();
            head.append(method).append(" ").append(requestTarget).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(hostHeader(endpoint)).append("\r\n");
            head.append("Accept: application/json, text/plain, */*\r\n");
            // ⚠️ 明确「不要压缩」：解压路径要多一套有界解压逻辑，
            //    而压缩膨胀（zip bomb）在出站链路上是纯粹的负担。
            head.append("Accept-Encoding: identity\r\n");
            head.append("Connection: close\r\n");
            head.append("User-Agent: agent-platform-http-tool/1\r\n");
            if (body != null) {
                head.append("Content-Type: application/json\r\n");
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");

            OutputStream out = io.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();

            return readResponse(io.getInputStream(), socket, deadline);
        } catch (SocketTimeoutException e) {
            throw HttpToolException.of(HttpToolException.Kind.TIMEOUT,
                    "HTTP 请求超时（上限 " + timeoutMs + " 毫秒）: host=" + endpoint.host(), e);
        } catch (IOException e) {
            throw HttpToolException.of(HttpToolException.Kind.CONNECT_FAILED,
                    "HTTP 连接失败: host=" + endpoint.host()
                            + " err=" + e.getClass().getSimpleName(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 读取 HTTP 响应。
     *
     * ⚠️ <b>不用 {@code BufferedReader}</b>：它会预读，
     * 把响应体的一部分吞进自己的缓冲区，导致「按 Content-Length 读体」时少读。
     * 故这里用自带缓冲的 {@link SocketReader}，头与体共用同一个缓冲。
     */
    private @NonNull RawResponse readResponse(@NonNull InputStream in,
                                              @NonNull Socket socket,
                                              long deadline)
            throws IOException {
        SocketReader reader = new SocketReader(in, socket, deadline);
        String statusLine = reader.readLine();
        if (statusLine == null || statusLine.isBlank()) {
            throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                    "HTTP 响应为空（连接建立后没有读到状态行）", null);
        }
        int status = parseStatus(statusLine);
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }

        byte[] body = readBody(reader, status, headers);
        String encoding = headers.get("content-encoding");
        if (encoding != null && !encoding.isBlank()
                && !"identity".equalsIgnoreCase(encoding.trim())) {
            body = decompress(encoding, body);
        }
        return new RawResponse(status, headers, body);
    }

    private @NonNull byte[] readBody(@NonNull SocketReader reader,
                                      int status,
                                      @NonNull Map<String, String> headers) throws IOException {
        int max = options.maxResponseBytes();
        boolean noBody = status == 204 || status == 304 || (status >= 100 && status < 200);
        if (noBody) {
            return new byte[0];
        }
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return reader.readChunked(max);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null && !contentLength.isBlank()) {
            long declared;
            try {
                declared = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                        "HTTP 响应的 Content-Length 非法: " + contentLength, e);
            }
            if (declared < 0) {
                throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                        "HTTP 响应的 Content-Length 为负: " + contentLength, null);
            }
            if (declared > max) {
                throw HttpToolException.of(HttpToolException.Kind.RESPONSE_TOO_LARGE,
                        "HTTP 响应体积 " + declared + " 字节超过上限 " + max + " 字节", null);
            }
            return reader.readExactly((int) declared);
        }
        return reader.readUntilEof(max);
    }

    /**
     * 有界解压。
     *
     * <p>⚠️ <b>解压后的体积同样要限</b>：压缩膨胀（zip bomb）能用几百字节
     * 撑出几百兆 —— 只限压缩前的体积等于没限。
     */
    private @NonNull byte[] decompress(@NonNull String encoding, @NonNull byte[] body) {
        String normalized = encoding.trim().toLowerCase(Locale.ROOT);
        try (InputStream source = switch (normalized) {
            case "gzip", "x-gzip" -> new GZIPInputStream(new java.io.ByteArrayInputStream(body));
            case "deflate" -> new InflaterInputStream(new java.io.ByteArrayInputStream(body));
            default -> throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                    "不支持的响应压缩编码: " + encoding, null);
        }) {
            return readAllBounded(source, options.maxResponseBytes());
        } catch (IOException e) {
            throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                    "HTTP 响应解压失败: " + encoding, e);
        }
    }

    private static @NonNull byte[] readAllBounded(@NonNull InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) > 0) {
            total += read;
            if (total > max) {
                throw HttpToolException.of(HttpToolException.Kind.RESPONSE_TOO_LARGE,
                        "解压后的响应体积超过上限 " + max + " 字节", null);
            }
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    // ── 杂项 ────────────────────────────────────────────────────────

    private static @NonNull URI parseUri(@NonNull String url, @NonNull String toolId) {
        try {
            return new URI(url.trim());
        } catch (URISyntaxException e) {
            throw HttpToolException.of(HttpToolException.Kind.CONFIG_INVALID,
                    "HTTP 工具 " + toolId + " 的目标 URL 非法: " + url, e);
        }
    }

    /**
     * 请求行里的目标（路径 + 查询串）。
     *
     * <p>⚠️ <b>查询串必须自己带上</b>：{@code McpEndpointGuard} 返回的
     * {@code path} 只有路径部分（它只关心主机与端口是否安全），
     * 而 GET 的语义全在查询串里 —— 丢了它，请求会「成功但拿到错误的数据」。
     */
    private static @NonNull String requestTarget(@NonNull URI uri,
                                                 McpEndpointGuard.@NonNull ValidatedEndpoint endpoint) {
        String target = endpoint.path();
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            target = target + "?" + query;
        }
        return target;
    }

    private static int parseStatus(@NonNull String statusLine) {
        int space = statusLine.indexOf(' ');
        if (space <= 0) {
            throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                    "HTTP 状态行非法: " + statusLine, null);
        }
        try {
            return Integer.parseInt(statusLine.substring(space + 1).trim().split("\\s+")[0]);
        } catch (RuntimeException e) {
            throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                    "HTTP 状态行非法: " + statusLine, e);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 是否因重定向而降级为 GET。
     *
     * <p>⚠️ <b>303 一律降级</b>（语义就是「去看另一个资源」）；
     * <b>301 / 302 只在原方法是 POST 时降级</b>（通用客户端的既有行为）；
     * <b>307 / 308 保持方法与请求体</b>（它们的存在意义就是「保持方法」）。
     */
    private static boolean redirectDowngrades(@NonNull String method, int status) {
        if (status == 303) {
            return true;
        }
        if (status == 301 || status == 302) {
            return "POST".equals(method);
        }
        return false;
    }

    private static @NonNull String hostHeader(McpEndpointGuard.@NonNull ValidatedEndpoint endpoint) {
        int defaultPort = "https".equals(endpoint.scheme()) ? 443 : 80;
        return endpoint.port() == defaultPort
                ? endpoint.host()
                : endpoint.host() + ":" + endpoint.port();
    }

    private static boolean isIpLiteral(@NonNull String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":");
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("关闭 HTTP 连接失败: {}", e.toString());
        }
    }

    /**
     * 带缓冲的 socket 读取器。
     *
     * <p>⚠️ <b>头与体必须共用同一个缓冲</b>：用 {@code BufferedReader} 读头
     * 会把响应体的开头吞进它的缓冲区，随后「按 Content-Length 读体」就会少读 ——
     * 那是一个只在特定响应长度下才显形的隐蔽缺陷。
     */
    private static final class SocketReader {

        private final InputStream in;
        private final Socket socket;
        private final long deadlineNanos;
        private final byte[] buffer = new byte[8192];
        private int position;
        private int limit;

        SocketReader(@NonNull InputStream in, @NonNull Socket socket, long deadlineNanos) {
            this.in = in;
            this.socket = socket;
            this.deadlineNanos = deadlineNanos;
        }

        private int readByte() throws IOException {
            if (position >= limit) {
                refill();
            }
            return position >= limit ? -1 : (buffer[position++] & 0xff);
        }

        private void refill() throws IOException {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException("HTTP 请求超出总时限");
            }
            // ⚠️ 每次补缓冲都把 SO_TIMEOUT 收紧到「剩余时间」——
            //    否则单次读超时是「总额」，总耗时可能翻倍。
            socket.setSoTimeout((int) Math.max(1, remaining / 1_000_000L));
            limit = in.read(buffer, 0, buffer.length);
            position = 0;
            if (limit < 0) {
                limit = 0;
            }
        }

        /**
         * 读一行（{@code CRLF} 或 {@code LF} 结尾均可）。
         *
         * @return 行内容；EOF 时返回 {@code null}
         */
        @Nullable String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);
            int current;
            while ((current = readByte()) != -1) {
                if (current == '\n') {
                    byte[] bytes = line.toByteArray();
                    int length = bytes.length;
                    if (length > 0 && bytes[length - 1] == '\r') {
                        length--;
                    }
                    return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
                }
                line.write(current);
            }
            return line.size() == 0 ? null
                    : line.toString(StandardCharsets.ISO_8859_1);
        }

        /**
         * 读满指定字节数。
         *
         * @param length 字节数
         * @return 读到的字节
         */
        @NonNull byte[] readExactly(int length) throws IOException {
            byte[] out = new byte[length];
            int read = 0;
            while (read < length) {
                int current = readByte();
                if (current == -1) {
                    throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                            "HTTP 响应体比 Content-Length 声明的短（连接被截断）", null);
                }
                out[read++] = (byte) current;
            }
            return out;
        }

        /**
         * 读到 EOF（响应未声明长度时的情形）。
         *
         * @param max 体积上限
         * @return 读到的字节
         */
        @NonNull byte[] readUntilEof(int max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            while (true) {
                int read = readSome(chunk);
                if (read <= 0) {
                    break;
                }
                total += read;
                if (total > max) {
                    throw HttpToolException.of(HttpToolException.Kind.RESPONSE_TOO_LARGE,
                            "HTTP 响应体积超过上限 " + max + " 字节", null);
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }

        /**
         * 读分块传输编码的响应体。
         *
         * @param max 体积上限
         * @return 读到的字节
         */
        @NonNull byte[] readChunked(int max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int total = 0;
            while (true) {
                String sizeLine = readLine();
                if (sizeLine == null) {
                    throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                            "HTTP 分块响应在块长度处被截断", null);
                }
                int semicolon = sizeLine.indexOf(';');
                String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
                int size;
                try {
                    size = Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    throw HttpToolException.of(HttpToolException.Kind.PROTOCOL_ERROR,
                            "HTTP 分块长度非法: " + sizeLine, e);
                }
                if (size == 0) {
                    // 读掉 trailer 部分
                    String trailer;
                    while ((trailer = readLine()) != null && !trailer.isEmpty()) {
                        // 丢弃即可
                    }
                    break;
                }
                total += size;
                if (total > max) {
                    throw HttpToolException.of(HttpToolException.Kind.RESPONSE_TOO_LARGE,
                            "HTTP 分块响应体积超过上限 " + max + " 字节", null);
                }
                out.write(readExactly(size));
                // 读掉块尾的 CRLF
                readLine();
            }
            return out.toByteArray();
        }

        private int readSome(@NonNull byte[] target) throws IOException {
            if (position >= limit) {
                refill();
            }
            if (position >= limit) {
                return -1;
            }
            int available = Math.min(target.length, limit - position);
            System.arraycopy(buffer, position, target, 0, available);
            position += available;
            return available;
        }
    }

    /**
     * 便于测试与排障：把 {@link JsonNode} 转文本。
     *
     * @param node 节点
     * @return 文本
     */
    static @NonNull String textOf(@NonNull JsonNode node) {
        return node.isTextual() ? node.asString() : node.toString();
    }
}
