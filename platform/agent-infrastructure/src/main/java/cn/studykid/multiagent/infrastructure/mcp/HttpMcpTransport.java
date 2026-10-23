package cn.studykid.multiagent.infrastructure.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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

import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.ports.mcp.McpCredentialPort;
import cn.studykid.multiagent.ports.mcp.McpTransportException;
import cn.studykid.multiagent.ports.mcp.McpTransportPort;

/**
 * MCP 的 http / sse 传输实现（W-PERM-P4-T1 · §6.5 · 04 §3）。
 *
 * <p>⚠️ <b>为什么不用 {@code java.net.http.HttpClient}</b>：
 * 它按<b>域名</b>发起连接 —— 即「校验过的 IP」与「实际连上的 IP」可能是两个东西，
 * 而 06 §5 要求「<b>解析与连接用同一结果</b>」。
 * 本类因此直接建连到<b>已校验的 IP</b>，并把原始主机名放进 {@code Host} 头；
 * HTTPS 场景用 {@code SSLSocketFactory.createSocket(socket, host, port, true)}，
 * 让 JDK 依旧按<b>主机名</b>做证书校验（SNI + endpoint identification），
 * 既钉死了目标 IP，又没有牺牲 TLS 的主机名校验。
 *
 * <p>⚠️ <b>为什么不做连接池</b>：
 * 池化连接会把「建立连接时的凭据」固化在连接上，
 * 凭据轮换后旧连接仍在用旧凭据 —— 这正是 06 §5「轮换后连接级重验」要防的事。
 * V1 选择<b>每请求一条新连接</b>（{@code Connection: close}），
 * 凭据逐请求解析，轮换立即生效。性能优化留给后续（P5 立项，需带凭据版本进池键）。
 *
 * <p>⚠️ <b>输出不可信</b>（§6.5）：远端返回的文本原样透传给调用方，
 * 由调用方在 Gate 边界做可信化处理；本类不做任何「清洗后即安全」的假设。
 */
public class HttpMcpTransport implements McpTransportPort {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpTransport.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 协议版本（V1）。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpEndpointGuard guard;
    private final McpCredentialPort credentials;
    private final McpTransportOptions options;
    private final McpCircuitBreaker breaker;
    private final Map<String, Semaphore> limiters = new ConcurrentHashMap<>();

    public HttpMcpTransport(@NonNull McpEndpointGuard guard,
                            @NonNull McpCredentialPort credentials,
                            @NonNull McpTransportOptions options,
                            @NonNull McpCircuitBreaker breaker) {
        this.guard = guard;
        this.credentials = credentials;
        this.options = options;
        this.breaker = breaker;
    }

    // ── McpTransportPort ────────────────────────────────────────────

    @Override
    public @NonNull List<McpToolInfo> listTools(@NonNull CapMcpServerDef server) {
        requireSupportedTransport(server);
        return guarded(server, () -> {
            String sessionId = initialize(server);
            ObjectNode params = MAPPER.createObjectNode();
            JsonNode result = rpc(server, sessionId, "tools/list", params);
            JsonNode tools = result == null ? null : result.get("tools");
            List<McpToolInfo> list = new ArrayList<>();
            if (tools != null && tools.isArray()) {
                for (JsonNode tool : tools) {
                    JsonNode name = tool.get("name");
                    if (name == null || name.isNull()) {
                        continue;
                    }
                    JsonNode description = tool.get("description");
                    JsonNode schema = tool.get("inputSchema");
                    list.add(new McpToolInfo(
                            name.asString(),
                            description == null || description.isNull() ? null : description.asString(),
                            schema == null || schema.isNull() ? null : schema.toString()));
                }
            }
            return list;
        });
    }

    @Override
    public @NonNull McpToolResult callTool(@NonNull CapMcpServerDef server,
                                           @NonNull String op,
                                           @NonNull Map<String, Object> arguments) {
        requireSupportedTransport(server);
        return guarded(server, () -> {
            String sessionId = initialize(server);
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", op);
            ObjectNode args = MAPPER.createObjectNode();
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                args.set(entry.getKey(), toNode(entry.getValue()));
            }
            params.set("arguments", args);

            JsonNode result = rpc(server, sessionId, "tools/call", params);
            if (result == null) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP tools/call 未返回结果: server=" + server.id() + " op=" + op);
            }
            StringBuilder text = new StringBuilder();
            boolean isError = false;
            JsonNode content = result.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode item : content) {
                    JsonNode type = item.get("type");
                    JsonNode value = item.get("text");
                    if (type != null && "text".equals(type.asString())
                            && value != null && !value.isNull()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(value.asString());
                    }
                }
            }
            JsonNode errorFlag = result.get("isError");
            if (errorFlag != null && !errorFlag.isNull()) {
                isError = Boolean.TRUE.equals(errorFlag.asBoolean(Boolean.FALSE));
            }
            JsonNode structured = result.get("structuredContent");
            Map<String, Object> structuredMap = null;
            if (structured != null && structured.isObject()) {
                structuredMap = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> entry : structured.properties()) {
                    structuredMap.put(entry.getKey(), toPlain(entry.getValue()));
                }
            }
            return new McpToolResult(text.toString(), structuredMap, isError);
        });
    }

    // ── 传输骨架 ────────────────────────────────────────────────────

    /**
     * 熔断 + 限流 + 失败计数的外壳。
     */
    private <T> T guarded(@NonNull CapMcpServerDef server,
                          java.util.function.@NonNull Supplier<T> action) {
        String serverId = server.id();
        if (!breaker.allowRequest(serverId)) {
            throw new McpTransportException(McpTransportException.Kind.CIRCUIT_OPEN,
                    "MCP Server 熔断打开，快速失败: " + serverId);
        }
        Semaphore limiter = limiters.computeIfAbsent(serverId,
                k -> new Semaphore(options.maxConcurrency()));
        boolean acquired = false;
        try {
            // ⚠️ 限流等待也要有上限：无限等待 = 一个慢 server 能把所有调用方挂住
            try {
                acquired = limiter.tryAcquire(options.readTimeout().toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // ⚠️ 恢复中断标志：吞掉它会让上层无法感知取消
                Thread.currentThread().interrupt();
                throw new McpTransportException(McpTransportException.Kind.OTHER,
                        "MCP 调用被中断: server=" + serverId, e);
            }
            if (!acquired) {
                throw new McpTransportException(McpTransportException.Kind.RATE_LIMITED,
                        "MCP Server 并发已达上限: " + serverId);
            }
            T result = action.get();
            breaker.recordSuccess(serverId);
            return result;
        } catch (McpTransportException e) {
            breaker.recordFailure(serverId);
            throw e;
        } catch (RuntimeException e) {
            breaker.recordFailure(serverId);
            throw new McpTransportException(McpTransportException.Kind.OTHER,
                    "MCP 传输失败: " + serverId, e);
        } finally {
            if (acquired) {
                limiter.release();
            }
        }
    }

    private void requireSupportedTransport(@NonNull CapMcpServerDef server) {
        if (!server.transport().isSupportedInV1()) {
            // ⚠️ stdio 在 V1 未启用（N7）：绝不因为「配置里写了」就去拉起子进程
            throw new McpTransportException(McpTransportException.Kind.OTHER,
                    "V1 仅支持 http / sse 传输，stdio 未启用: server=" + server.id());
        }
    }

    /**
     * MCP 握手（{@code initialize}），返回会话 id。
     */
    private @NonNull String initialize(@NonNull CapMcpServerDef server) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = MAPPER.createObjectNode();
        params.set("capabilities", capabilities);
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "agent-platform");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);

        RawResponse response = send(server, "initialize", MAPPER.writeValueAsString(params));
        return response.sessionId() == null ? "" : response.sessionId();
    }

    /**
     * 发起一次 JSON-RPC 调用并返回 {@code result}。
     */
    private @Nullable JsonNode rpc(@NonNull CapMcpServerDef server,
                                   @NonNull String sessionId,
                                   @NonNull String method,
                                   @NonNull ObjectNode params) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", method);
        envelope.set("params", params);

        RawResponse response = send(server, MAPPER.writeValueAsString(envelope), sessionId);
        JsonNode body = parseJsonRpc(response);
        if (body == null) {
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP 响应不是合法 JSON-RPC: server=" + server.id() + " method=" + method);
        }
        JsonNode error = body.get("error");
        if (error != null && !error.isNull()) {
            JsonNode message = error.get("message");
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP 返回错误: server=" + server.id() + " method=" + method
                            + " message=" + (message == null ? "" : message.asString()));
        }
        return body.get("result");
    }

    // ── HTTP（IP 绑定）─────────────────────────────────────────────

    /**
     * 发送一次请求（含重定向逐跳复验）。
     */
    private @NonNull RawResponse send(@NonNull CapMcpServerDef server,
                                      @NonNull String body,
                                      @Nullable String sessionId) {
        return send(server, body, sessionId, 0);
    }

    private @NonNull RawResponse send(@NonNull CapMcpServerDef server,
                                      @NonNull String body,
                                      @Nullable String sessionId,
                                      int redirectHop) {
        if (redirectHop > options.maxRedirects()) {
            throw new McpTransportException(McpTransportException.Kind.SSRF_BLOCKED,
                    "MCP 重定向跳数超限: server=" + server.id());
        }
        // 每次发送前都重新校验端点（06 §5「每次连接与重定向重新校验目标地址」）
        McpEndpointGuard.ValidatedEndpoint endpoint = guard.validate(server);
        RawResponse response = exchange(server, endpoint, body, sessionId);

        if (response.isRedirect()) {
            String location = response.header("location");
            if (location == null || location.isBlank()) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP 返回重定向但缺少 Location: server=" + server.id());
            }
            String target = resolveLocation(endpoint, location);
            // ⚠️ 重定向目标必须重新走完整校验（解析 + IP 判定 + 二次解析）
            //    ——「允许重定向」不等于「允许重定向到内网」。
            guard.validate(target, server.trustTier(), server.id() + "#redirect");
            return send(server, body, sessionId, redirectHop + 1);
        }
        if (!response.isSuccess()) {
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP HTTP 状态异常: server=" + server.id() + " status=" + response.status());
        }
        return response;
    }

    private @NonNull String resolveLocation(McpEndpointGuard.@NonNull ValidatedEndpoint endpoint,
                                            @NonNull String location) {
        try {
            URI base = new URI(endpoint.scheme() + "://" + endpoint.host()
                    + (endpoint.port() == defaultPort(endpoint.scheme())
                            ? "" : ":" + endpoint.port()));
            URI resolved = base.resolve(location.trim());
            return resolved.toString();
        } catch (java.net.URISyntaxException e) {
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP 重定向 Location 非法", e);
        }
    }

    private static int defaultPort(@NonNull String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /**
     * 一次真实的 HTTP 交换：连到<b>已校验的 IP</b>，Host 头用原始主机名。
     */
    private @NonNull RawResponse exchange(@NonNull CapMcpServerDef server,
                                          McpEndpointGuard.@NonNull ValidatedEndpoint endpoint,
                                          @NonNull String body,
                                          @Nullable String sessionId) {
        Optional<McpCredentialPort.McpCredential> credential =
                credentials.resolve(server.credentialRef());

        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(endpoint.primaryAddress(), endpoint.port()),
                    (int) options.connectTimeout().toMillis());
            socket.setSoTimeout((int) options.readTimeout().toMillis());

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

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("POST ").append(endpoint.path()).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(hostHeader(endpoint)).append("\r\n");
            head.append("Content-Type: application/json\r\n");
            head.append("Accept: application/json, text/event-stream\r\n");
            head.append("Content-Length: ").append(payload.length).append("\r\n");
            if (sessionId != null && !sessionId.isBlank()) {
                head.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
            }
            // ⚠️ 凭据逐请求解析：轮换后立即生效（06 §5「轮换后连接级重验」）
            credential.ifPresent(c -> applyCredential(head, c));
            // ⚠️ 不复用连接：连接池会把旧凭据固化在连接上
            head.append("Connection: close\r\n");
            head.append("\r\n");

            OutputStream out = io.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(payload);
            out.flush();

            return readResponse(io.getInputStream());
        } catch (java.net.SocketTimeoutException e) {
            throw new McpTransportException(McpTransportException.Kind.TIMEOUT,
                    "MCP 传输超时: server=" + server.id(), e);
        } catch (IOException e) {
            throw new McpTransportException(McpTransportException.Kind.CONNECT_FAILED,
                    "MCP 连接失败: server=" + server.id() + " err=" + e.getClass().getSimpleName(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    private static void applyCredential(@NonNull StringBuilder head,
                                        McpCredentialPort.@NonNull McpCredential credential) {
        String secret = credential.secret();
        if (secret == null || secret.isBlank()) {
            return;
        }
        switch (credential.kind()) {
            case TOKEN -> head.append("Authorization: Bearer ").append(secret).append("\r\n");
            case OAUTH -> head.append("Authorization: Bearer ").append(secret).append("\r\n");
            case NONE -> {
                // 无凭据：不加头
            }
        }
    }

    private static @NonNull String hostHeader(McpEndpointGuard.@NonNull ValidatedEndpoint endpoint) {
        boolean defaultPort = endpoint.port() == defaultPort(endpoint.scheme());
        return defaultPort ? endpoint.host() : endpoint.host() + ":" + endpoint.port();
    }

    private static boolean isIpLiteral(@NonNull String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":");
    }

    // ── HTTP 响应解析 ──────────────────────────────────────────────

    /**
     * 原始 HTTP 响应。
     *
     * @param status    状态码
     * @param headers   响应头（小写键）
     * @param body      响应体
     * @param sessionId MCP 会话 id
     */
    record RawResponse(int status, Map<String, String> headers, String body,
                       @Nullable String sessionId) {

        boolean isRedirect() {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        @Nullable String header(@NonNull String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private @NonNull RawResponse readResponse(@NonNull InputStream in) {
        try {
            String statusLine = readLine(in);
            if (statusLine == null || statusLine.isBlank()) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP 响应为空");
            }
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP 响应状态行非法: " + statusLine);
            }
            int status = Integer.parseInt(parts[1].trim());

            Map<String, String> headers = new java.util.LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                        line.substring(idx + 1).trim());
            }

            String body = readBody(in, headers);
            String sessionId = headers.get("mcp-session-id");
            return new RawResponse(status, headers, body, sessionId);
        } catch (NumberFormatException e) {
            throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                    "MCP 响应状态码非法", e);
        } catch (IOException e) {
            throw new McpTransportException(McpTransportException.Kind.CONNECT_FAILED,
                    "MCP 响应读取失败", e);
        }
    }

    private @NonNull String readBody(@NonNull InputStream in,
                                     @NonNull Map<String, String> headers) throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int length = Integer.parseInt(contentLength.trim());
            if (length > options.maxResponseBytes()) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP 响应超出体积上限: " + length + " > " + options.maxResponseBytes());
            }
            byte[] buffer = new byte[Math.max(0, length)];
            int read = 0;
            while (read < length) {
                int n = in.read(buffer, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
        // 无长度信息 → 读到 EOF（Connection: close 保证会结束）
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
            if (buffer.size() > options.maxResponseBytes()) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP 响应超出体积上限: > " + options.maxResponseBytes());
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private @NonNull String readChunked(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
                readLine(in);
                break;
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int n = in.read(chunk, read, size - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            buffer.write(chunk, 0, read);
            if (buffer.size() > options.maxResponseBytes()) {
                throw new McpTransportException(McpTransportException.Kind.PROTOCOL_ERROR,
                        "MCP 响应超出体积上限: > " + options.maxResponseBytes());
            }
            readLine(in);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static @Nullable String readLine(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            buffer.write(b);
        }
        return buffer.size() == 0 ? null
                : buffer.toString(StandardCharsets.ISO_8859_1);
    }

    // ── JSON-RPC 解析 ──────────────────────────────────────────────

    /**
     * 解析 JSON-RPC 响应体。
     *
     * <p>⚠️ <b>同时支持 {@code application/json} 与 {@code text/event-stream}</b>：
     * http 传输通常直接回 JSON，sse 传输回 SSE 帧流。
     * 两者最终都要拿到那一个 JSON-RPC 响应对象。
     */
    private @Nullable JsonNode parseJsonRpc(@NonNull RawResponse response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        String contentType = response.header("content-type");
        boolean sse = contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
        String payload = sse ? extractSsePayload(body) : body;
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 从 SSE 帧流里取出 JSON-RPC 响应。
     *
     * <p>SSE 帧形如 {@code data: {...}}，多帧时取<b>最后一个</b>可解析为 JSON-RPC 的帧
     * （服务端可能先推通知再推响应）。
     */
    private @Nullable String extractSsePayload(@NonNull String body) {
        StringBuilder candidate = null;
        String best = null;
        for (String rawLine : body.split("\r?\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                if (candidate != null && candidate.length() > 0) {
                    String text = candidate.toString();
                    best = text;
                    candidate = null;
                }
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if (candidate == null) {
                candidate = new StringBuilder();
            } else {
                candidate.append('\n');
            }
            candidate.append(data);
        }
        if (candidate != null && candidate.length() > 0) {
            best = candidate.toString();
        }
        return best;
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private static @Nullable JsonNode toNode(@Nullable Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        return MAPPER.valueToTree(value);
    }

    private static @Nullable Object toPlain(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                map.put(entry.getKey(), toPlain(entry.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(toPlain(item));
            }
            return list;
        }
        if (node.isBoolean()) {
            return node.asBoolean(Boolean.FALSE);
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asString();
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("关闭 MCP 连接失败: {}", e.getMessage());
        }
    }
}
