package cn.studykid.rag.infrastructure.premise;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;

import cn.studykid.rag.infrastructure.parsing.MineruResponseMapper;
import cn.studykid.rag.infrastructure.parsing.MineruResponseMapper.MineruUnavailableException;
import cn.studykid.rag.ports.LayoutBlock;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * MinerU 解析后端（RAG-D26 阶段 1.2）——Tasks API 客户端 + MiddleJson 映射。
 *
 * <p><b>⚠️ 接口契约来自实测（2026-11-06，MinerU 4.1.7 自托管 api-server）</b>：
 * <ol>
 *   <li>{@code POST /v1/tasks}（application/json）：
 *       {@code {"tier":"flash|basic|standard","output_formats":["default"],
 *       "files":[{"file_name":…,"bytes":…,"sha256sum":…}]}}；</li>
 *   <li>响应 {@code files[i].upload = {url, headers, expires_at}}——
 *       用<b>原生 PUT</b> 上传字节（带返回的 headers）；
 *       服务端按 sha256 去重（同内容秒传，不重复上传）；</li>
 *   <li>{@code GET /v1/tasks/{task_id}} 轮询：task 状态
 *       {@code waiting-file → pending → running → completed|partial|failed|canceled}；
 *       文件级 {@code result_url} 在 completed 后才出现；</li>
 *   <li>{@code result_url} 指向 ZIP（成员 {@code markdown.md / middle_json.json /
 *       structured_content.json / images/}）——解析目标就是 {@code middle_json.json}；</li>
 *   <li><b>无列表端点</b>：任务详情必须持有 task_id（本类每次解析新建任务，无需列表）。</li>
 * </ol>
 *
 * <p><b>⚠️ 与 Docling 适配器的同构决策</b>：
 * 整册同步解析在 Docling 上实测 710 秒不可行；MinerU 侧整册 162 页实测约 200 秒
 * （64 次轮询 × 3 秒），且 Tasks API 本身是异步任务——<b>不分段</b>，
 * 直接整册提交（MinerU 的并发=1 由服务端控制，页级分段只会增加排队次数）。
 *
 * <p><b>⚠️ fail-closed 继承</b>：服务不可达 / 任务失败 / ZIP 缺 middle_json /
 * 映射失败一律抛 {@link MineruUnavailableException}（Job 置 FAILED），
 * <b>绝不</b>回落到 Docling 或占位——「配了 mineru 却静默用了别的后端」
 * 会让 parser_version 留痕失真。
 */
public class MineruPremiseBackend implements DocumentParseBackend {

    private static final Logger log = LoggerFactory.getLogger(MineruPremiseBackend.class);

    /** 默认轮询间隔（秒）。实测 162 页整册约 200 秒，3 秒粒度足够。 */
    public static final int DEFAULT_POLL_INTERVAL_SECONDS = 3;

    /** 默认总超时（秒）：600 秒覆盖到 500 页量级（实测 0.8 页/秒）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final String baseUrl;
    private final String tier;
    private final int timeoutSeconds;
    private final int pollIntervalSeconds;
    private final MineruResponseMapper mapper;
    private final HttpClient http;

    /**
     * @param baseUrl        MinerU api-server 基址（如 {@code http://127.0.0.1:8000}）
     * @param tier           解析档位（flash/basic/standard/advanced；扫描版建议 standard）
     * @param timeoutSeconds 总超时（提交到取回结果的预算）
     */
    public MineruPremiseBackend(@NonNull String baseUrl, @NonNull String tier,
                                int timeoutSeconds) {
        this(baseUrl, tier, timeoutSeconds, DEFAULT_POLL_INTERVAL_SECONDS,
                new MineruResponseMapper(null));
    }

    /** 全参构造（测试注入轮询间隔与 mapper）。 */
    public MineruPremiseBackend(@NonNull String baseUrl, @NonNull String tier,
                                int timeoutSeconds, int pollIntervalSeconds,
                                MineruResponseMapper mapper) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空（MinerU 后端必须显式配置端点）");
        }
        this.baseUrl = trimmed;
        this.tier = tier == null || tier.isBlank() ? "standard" : tier.trim();
        this.timeoutSeconds = Math.max(30, timeoutSeconds);
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.mapper = mapper == null ? new MineruResponseMapper(null) : mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public @NonNull String backendId() {
        return "mineru";
    }

    @Override
    public @NonNull String backendVersion() {
        // 真实版本来自每次解析响应的 metadata.producer.version（写入 ParseTrace）；
        // 此处返回档位作为静态标识（版本未知为空串的契约不受影响——trace 里有真值）。
        return tier;
    }

    @Override
    public @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        if (content.length == 0) {
            throw new MineruUnavailableException("文档内容为空（documentId=" + documentId + "）");
        }
        // ① 提交任务
        String taskId = createTask(documentId, content);
        // ② 轮询至终态
        String resultUrl = pollUntilDone(documentId, taskId);
        // ③ 下载 ZIP 并取 middle_json.json
        String middleJson = downloadMiddleJson(documentId, resultUrl);
        // ④ 映射（含 0 基页码 +1 换算与四项 Java 侧计算）
        List<LayoutBlock> blocks = mapper.map(documentId, middleJson);
        // 版本号从响应取（映射器不回传，这里从 middle_json 再读一次——
        // 更优做法是 mapper 连同版本一起返回，见 trace 组装）
        String version = extractProducerVersion(middleJson);
        ParseTrace trace = new ParseTrace(backendId(), version,
                blocks.stream().mapToInt(LayoutBlock::pageNumber).max().orElse(0),
                false, 1, List.of());
        log.info("[RAG] MinerU 解析完成 documentId={} 页数={} 块数={} tier={} version={}",
                documentId, trace.pageCount(), blocks.size(), tier, version);
        return new PremiseDocument(documentId, PremiseBlock.fromLayoutBlocks(blocks), trace);
    }

    // ---------- Tasks API 三步 ----------

    private String createTask(String documentId, byte[] content) {
        String sha = sha256Hex(content);
        // ⚠️ files[i].file_name 是逻辑名（不能含路径分隔符）；用 documentId 保证可追溯
        String body = "{"
                + "\"tier\":\"" + tier + "\","
                + "\"output_formats\":[\"default\"],"
                + "\"files\":[{\"file_name\":\"" + documentId + ".pdf\","
                + "\"bytes\":" + content.length + ","
                + "\"sha256sum\":\"" + sha + "\"}]"
                + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = send(req, "创建解析任务");
        JsonNode root = readJson(resp.body(), "创建任务响应");
        String taskId = root.path("task_id").asText("");
        if (taskId.isEmpty()) {
            throw new MineruUnavailableException("创建任务响应缺少 task_id（documentId=" + documentId + "）");
        }
        // upload 链接（sha 未命中缓存时返回；命中则直接 pending）
        JsonNode upload = root.path("files").path(0).path("upload");
        if (!upload.isMissingNode() && !upload.isNull()) {
            uploadBytes(documentId, upload, content);
        } else if (isWaitingFile(root)) {
            throw new MineruUnavailableException(
                    "任务处于 waiting-file 但未返回 upload 链接（documentId=" + documentId + "）");
        }
        return taskId;
    }

    private static boolean isWaitingFile(JsonNode root) {
        return "waiting-file".equals(root.path("status").asText(""));
    }

    private void uploadBytes(String documentId, JsonNode upload, byte[] content) {
        String url = upload.path("url").asText("");
        if (url.isEmpty()) {
            throw new MineruUnavailableException("upload 链接缺少 url（documentId=" + documentId + "）");
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .timeout(Duration.ofSeconds(120));
        upload.path("headers").properties()
                .forEach(entry -> b.header(String.valueOf(entry.getKey()),
                        String.valueOf(entry.getValue())));
        HttpResponse<String> resp = send(b.build(), "上传文档字节");
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new MineruUnavailableException("上传失败 HTTP " + resp.statusCode()
                    + "（documentId=" + documentId + "）");
        }
    }

    private String pollUntilDone(String documentId, String taskId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/tasks/" + taskId))
                    .GET().timeout(Duration.ofSeconds(30)).build();
            HttpResponse<String> resp = send(req, "轮询任务状态");
            JsonNode root = readJson(resp.body(), "任务详情");
            String status = root.path("status").asText("");
            switch (status) {
                case "completed" -> {
                    String url = root.path("files").path(0).path("result_url").asText("");
                    if (url.isEmpty()) {
                        throw new MineruUnavailableException(
                                "任务 completed 但缺 result_url（documentId=" + documentId + "）");
                    }
                    return url;
                }
                case "failed", "canceled" -> throw new MineruUnavailableException(
                        "MinerU 任务 " + status + "（documentId=" + documentId + "，error="
                                + root.path("error").toString() + "）");
                case "partial" -> throw new MineruUnavailableException(
                        "MinerU 任务部分完成（partial 不满足整册原子性，documentId="
                                + documentId + "）——单文件任务的 partial 等价于失败");
                default -> { /* waiting-file / pending / running → 继续轮询 */ }
            }
            try {
                Thread.sleep(pollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MineruUnavailableException("轮询被中断（documentId=" + documentId + "）", e);
            }
        }
        throw new MineruUnavailableException(
                "MinerU 任务超时（>" + timeoutSeconds + "s，documentId=" + documentId
                        + "，taskId=" + taskId + "）");
    }

    private String downloadMiddleJson(String documentId, String resultUrl) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(resultUrl))
                .GET().timeout(Duration.ofSeconds(120)).build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new MineruUnavailableException("下载结果 ZIP 失败（documentId=" + documentId + "）", e);
        }
        if (resp.statusCode() != 200) {
            throw new MineruUnavailableException("下载结果 ZIP HTTP " + resp.statusCode()
                    + "（documentId=" + documentId + "）");
        }
        // ZIP 流式扫描：只取 middle_json.json（不落盘，内存中完成）
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(resp.body()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith("middle_json.json")) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    zip.transferTo(buf);
                    return buf.toString(StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new MineruUnavailableException("结果 ZIP 解析失败（documentId=" + documentId + "）", e);
        }
        throw new MineruUnavailableException(
                "结果 ZIP 中没有 middle_json.json（documentId=" + documentId
                        + "，output_formats 是否漏了 default？）");
    }

    // ---------- HTTP 与工具 ----------

    private HttpResponse<String> send(HttpRequest req, String action) {
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new MineruUnavailableException(action + "失败 HTTP " + resp.statusCode()
                        + "：" + abbreviate(resp.body()));
            }
            return resp;
        } catch (IOException e) {
            throw new MineruUnavailableException(action + "失败（连接错误）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MineruUnavailableException(action + "被中断", e);
        }
    }

    private JsonNode readJson(String body, String what) {
        try {
            return mapper.getObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new MineruUnavailableException(what + "不是合法 JSON", e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private String extractProducerVersion(String middleJson) {
        try {
            JsonNode root = mapper.getObjectMapper().readTree(middleJson);
            String v = root.path("metadata").path("producer").path("version").asText("");
            return v; // 空串 = 未知（契约允许）
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * @return 是否已配置（构造器已强制非空；保留供装配自检对称性）
     */
    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }
}
