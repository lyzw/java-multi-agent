package cn.studykid.multiagent.api.admin;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 模型统一配置管理端点（管理后台集中维护模型目录）。
 *
 * <p><b>⚠️ 两类端点、两种鉴权</b>：
 * <ul>
 *   <li>{@code /api/admin/models/**}：管理后台 UI 调用，需 {@code PLATFORM_ADMIN} 权限
 *       （超级管理员集中维护，所有租户共用，故不是租户级 {@code AGENT_MANAGE}）；</li>
 *   <li>{@code /api/internal/models/resolve}：服务间（如 RAG reactor）按场景解析模型，
 *       走内部共享密钥（{@code X-Platform-Token}），不依赖用户令牌——
 *       <b>响应绝不携带 API Key</b>（密钥按 provider 由环境变量解析）。</li>
 * </ul>
 *
 * <p><b>⚠️ 诚实性铁律（与 {@code ConfigReadService} 同口径）</b>：本端点返回的是
 * <b>目录元数据</b>（provider / 场景 / 模型名 / 能力 / 地址），<b>不含任何密钥</b>。
 * 即便将来有人想加 key 字段，也必须从响应里剥离，否则视为安全缺陷。
 *
 * <p><b>⚠️ 写入即生效、无缓存</b>：{@code ModelResolver} 每次读端口，故管理后台改完
 * 下一次解析即生效，无需重启。
 */
@RestController
@RequestMapping("/api/admin/models")
public class AdminModelConfigController {

    private final ModelCatalogPort catalog;
    private final ModelResolver resolver;
    private final AuthorizationService authorization;
    private final CurrentPrincipal currentPrincipal;

    /** 内部服务间共享密钥（环境注入）；为空表示开发模式放行（生产必须设置）。 */
    private final String internalToken;

    public AdminModelConfigController(ModelCatalogPort catalog,
                                      ModelResolver resolver,
                                      AuthorizationService authorization,
                                      CurrentPrincipal currentPrincipal,
                                      @Value("${model-catalog.internal-token:}") String internalToken) {
        this.catalog = catalog;
        this.resolver = resolver;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    /** 列出全部模型定义（按场景 / 排序权重）。 */
    @GetMapping
    public ResponseEntity<List<ModelConfigResponse>> list() {
        requireAdmin();
        return ResponseEntity.ok(catalog.list().stream().map(ModelConfigResponse::from).toList());
    }

    /** 取单条。 */
    @GetMapping("/{id}")
    public ResponseEntity<ModelConfigResponse> get(@PathVariable String id) {
        requireAdmin();
        return catalog.get(id)
                .map(def -> ResponseEntity.ok(ModelConfigResponse.from(def)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型定义不存在"));
    }

    /** 新增模型定义。 */
    @PostMapping
    public ResponseEntity<ModelConfigResponse> create(@RequestBody ModelConfigRequest req) {
        requireAdmin();
        ModelDefinition saved = catalog.save(toDefinition(null, req));
        return ResponseEntity.status(HttpStatus.CREATED).body(ModelConfigResponse.from(saved));
    }

    /** 更新模型定义（全量覆盖，按 id）。 */
    @PutMapping("/{id}")
    public ResponseEntity<ModelConfigResponse> update(@PathVariable String id,
                                                     @RequestBody ModelConfigRequest req) {
        requireAdmin();
        if (catalog.get(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型定义不存在");
        }
        ModelDefinition saved = catalog.save(toDefinition(id, req));
        return ResponseEntity.ok(ModelConfigResponse.from(saved));
    }

    /** 删除模型定义。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        requireAdmin();
        catalog.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── 鉴权辅助 ──

    private void requireAdmin() {
        authorization.requireAuthorized(currentPrincipal.context(),
                AuthorizationService.Roles.PLATFORM_ADMIN, null);
    }

    private void assertInternal(@Nullable String token) {
        if (!internalToken.isBlank()) {
            if (token == null || !internalToken.equals(token)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效的内部令牌");
            }
        }
    }

    private static ModelDefinition toDefinition(@Nullable String id, ModelConfigRequest req) {
        return new ModelDefinition(
                id,
                req.provider(),
                req.scenario(),
                req.modelName(),
                req.displayName(),
                req.baseUrl(),
                req.isDefault(),
                req.supportsTools(),
                req.supportsStreaming(),
                req.supportsVision(),
                req.dimension(),
                req.isEnabled(),
                req.sortOrder(),
                req.description(),
                null, null);
    }

    /** 创建 / 更新请求体（不含 id 与时间戳）。 */
    public record ModelConfigRequest(
            String provider,
            String scenario,
            String modelName,
            String displayName,
            String baseUrl,
            boolean isDefault,
            boolean supportsTools,
            boolean supportsStreaming,
            boolean supportsVision,
            Integer dimension,
            boolean isEnabled,
            int sortOrder,
            String description) {
    }

    /** 列表 / 详情响应（不含密钥）。 */
    public record ModelConfigResponse(
            String id,
            String provider,
            String scenario,
            String modelName,
            String displayName,
            String baseUrl,
            boolean isDefault,
            boolean supportsTools,
            boolean supportsStreaming,
            boolean supportsVision,
            Integer dimension,
            boolean isEnabled,
            int sortOrder,
            String description,
            Instant createdAt,
            Instant updatedAt) {

        static ModelConfigResponse from(ModelDefinition d) {
            return new ModelConfigResponse(d.id(), d.provider(), d.scenario(), d.modelName(),
                    d.displayName(), d.baseUrl(), d.isDefault(), d.supportsTools(),
                    d.supportsStreaming(), d.supportsVision(), d.dimension(), d.isEnabled(),
                    d.sortOrder(), d.description(), d.createdAt(), d.updatedAt());
        }
    }

    /** 内部解析响应（服务间消费，<b>不含密钥</b>）。 */
    public record ModelResolveResponse(
            String scenario,
            String provider,
            String modelName,
            String baseUrl,
            Integer dimension,
            boolean supportsTools,
            boolean supportsStreaming,
            boolean supportsVision) {

        static ModelResolveResponse from(ModelDefinition d, String scenario) {
            return new ModelResolveResponse(scenario, d.provider(), d.modelName(), d.baseUrl(),
                    d.dimension(), d.supportsTools(), d.supportsStreaming(), d.supportsVision());
        }
    }
}
