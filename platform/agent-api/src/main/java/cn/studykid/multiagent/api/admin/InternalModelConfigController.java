package cn.studykid.multiagent.api.admin;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;

/**
 * 模型配置的服务间解析端点（供 RAG 等独立 reactor 消费统一模型目录）。
 *
 * <p><b>⚠️ 仅内部服务调用</b>：不走用户令牌，改走共享密钥 {@code X-Platform-Token}
 * （环境注入 {@code model-catalog.internal-token}）。生产必须设置该令牌；
 * 为空时放行（开发模式），便于本地联调。
 *
 * <p><b>⚠️ 响应绝不携带 API Key</b>：只回模型元数据（provider / 模型名 / 地址 / 能力 / 维度），
 * 密钥由调用方按 {@code provider} 从自身环境变量解析
 * （与平台侧 {@code ModelCredentialProvider} 同源约定）。
 */
@RestController
@RequestMapping("/api/internal/models")
public class InternalModelConfigController {

    private final ModelResolver resolver;
    private final String internalToken;

    public InternalModelConfigController(ModelResolver resolver,
                                        @Value("${model-catalog.internal-token:}") String internalToken) {
        this.resolver = resolver;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    /**
     * 按场景（可选显式模型名）解析启用中的模型。
     *
     * @param scenario 场景 code（必需，如 {@code embedding}）
     * @param model    显式模型名（可选）
     * @param token    内部共享密钥
     */
    @GetMapping("/resolve")
    public ResponseEntity<AdminModelConfigController.ModelResolveResponse> resolve(
            @RequestParam String scenario,
            @RequestParam(required = false) String model,
            @RequestHeader(value = "X-Platform-Token", required = false) String token) {
        if (!internalToken.isBlank() && (token == null || !internalToken.equals(token))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效的内部令牌");
        }
        ModelDefinition def = resolver.resolve(scenario, model).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "场景 [" + scenario + "] 无可用模型"));
        return ResponseEntity.ok(AdminModelConfigController.ModelResolveResponse.from(def, scenario));
    }
}
