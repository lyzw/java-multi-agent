package cn.studykid.multiagent.infrastructure.http;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.ports.http.HttpToolHandlerProvider;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * 从权威配置解析 HTTP-backed 工具处理器（第三十七批 · ADR-011 / 07 §3）。
 *
 * <p>⚠️ <b>版本口径必须与闭包解析一致</b>：
 * 本类用 {@code PublishedVersionPort}（当前发布指针）解析版本，
 * 与 {@code PermissionResolver}、{@code DbMcpHandlerProvider} 完全相同。
 * 若这里改用「最新已发布行」，处理器拿到的入参 Schema / 目标 URL
 * 可能与闭包里固化的那一版<b>不是同一版</b> ——
 * 于是「授权按 A 版判定、执行按 B 版跑」。
 *
 * <p>⚠️ <b>解析不到就是解析不到</b>：不猜、不回退到「按工具名直接发请求」。
 */
public class DbHttpToolHandlerProvider implements HttpToolHandlerProvider {

    private static final Logger log = LoggerFactory.getLogger(DbHttpToolHandlerProvider.class);

    private final CapToolDefPort toolDefs;
    private final PublishedVersionPort publishedVersions;
    private final HttpToolExecutor executor;
    private final Map<String, HttpBackedToolHandler> cache = new ConcurrentHashMap<>();

    public DbHttpToolHandlerProvider(@NonNull CapToolDefPort toolDefs,
                                     @NonNull PublishedVersionPort publishedVersions,
                                     @NonNull HttpToolExecutor executor) {
        this.toolDefs = toolDefs;
        this.publishedVersions = publishedVersions;
        this.executor = executor;
    }

    @Override
    public @NonNull Optional<ToolHandler> resolve(@NonNull String toolName) {
        Optional<Integer> toolVersion =
                publishedVersions.currentVersion(CapObjectType.TOOL, toolName);
        if (toolVersion.isEmpty()) {
            return Optional.empty();
        }
        Optional<CapToolDef> tool = toolDefs.find(toolName, toolVersion.get());
        if (tool.isEmpty()) {
            return Optional.empty();
        }
        if (!tool.get().isHttp() || tool.get().httpConfig() == null) {
            // ⚠️ 不是 HTTP 工具 → 本类不处理（原生工具走静态注册表，MCP 工具走 MCP 解析器）
            return Optional.empty();
        }

        String cacheKey = toolName + "@" + toolVersion.get();
        HttpBackedToolHandler cached = cache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        HttpBackedToolHandler handler = new HttpBackedToolHandler(tool.get(), executor);
        cache.put(cacheKey, handler);
        return Optional.of(handler);
    }

    /**
     * 丢弃全部缓存（配置发布后显式失效）。
     */
    public void invalidate() {
        cache.clear();
    }
}
