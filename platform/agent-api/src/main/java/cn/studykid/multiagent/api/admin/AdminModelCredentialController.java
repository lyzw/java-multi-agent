package cn.studykid.multiagent.api.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort.ProviderCredentialView;
import cn.studykid.multiagent.ports.security.AuthorizationService;

/**
 * 供应商密钥管理端点（带外加密存储）。
 *
 * <p><b>⚠️ 密钥与目录分离</b>：模型目录（{@code /api/admin/models}）只存配置、不含密钥；
 * 本端点专管按 provider 的 API Key，落库前经 AES-256-GCM 加密。
 *
 * <p><b>⚠️ 鉴权</b>：需 {@code PLATFORM_ADMIN}（超级管理员集中维护，所有租户共用）。
 *
 * <p><b>⚠️ 诚实性铁律</b>：响应<b>绝不携带明文密钥</b>——
 * 列表/单条都返回脱敏视图（仅末 4 位可见），
 * 明文仅在运行时由 {@link ProviderCredentialPort#resolveApiKey(String)} 内部解析供网关使用。
 * 即便将来有人想加明文回显，也必须剥离，否则视为安全缺陷。
 */
@RestController
@RequestMapping("/api/admin/model-credentials")
public class AdminModelCredentialController {

    private final ProviderCredentialPort credentialPort;
    private final AuthorizationService authorization;
    private final CurrentPrincipal currentPrincipal;

    public AdminModelCredentialController(ProviderCredentialPort credentialPort,
                                          AuthorizationService authorization,
                                          CurrentPrincipal currentPrincipal) {
        this.credentialPort = credentialPort;
        this.authorization = authorization;
        this.currentPrincipal = currentPrincipal;
    }

    /** 列出全部供应商密钥（脱敏视图）。 */
    @GetMapping
    public ResponseEntity<List<ProviderCredentialView>> list() {
        requireAdmin();
        return ResponseEntity.ok(credentialPort.listMasked());
    }

    /** 单条脱敏视图。 */
    @GetMapping("/{provider}")
    public ResponseEntity<ProviderCredentialView> get(@PathVariable String provider) {
        requireAdmin();
        return credentialPort.masked(provider)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "该供应商尚未配置密钥"));
    }

    /** 写入 / 覆盖某 provider 的密钥（明文仅上传、不回显）。 */
    @PutMapping
    public ResponseEntity<ProviderCredentialView> save(
            @RequestBody ProviderCredentialRequest req) {
        requireAdmin();
        if (req.provider() == null || req.provider().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider 不能为空");
        }
        if (req.apiKey() == null || req.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey 不能为空");
        }
        credentialPort.save(req.provider(), req.apiKey());
        return credentialPort.masked(req.provider())
                .map(v -> ResponseEntity.status(HttpStatus.OK).body(v))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "密钥写入后无法读取脱敏视图"));
    }

    /** 删除某 provider 的密钥。 */
    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> delete(@PathVariable String provider) {
        requireAdmin();
        credentialPort.delete(provider);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin() {
        authorization.requireAuthorized(currentPrincipal.context(),
                AuthorizationService.Roles.PLATFORM_ADMIN, null);
    }

    /** 写入请求体（明文仅上传瞬时处理，不落库、不回显）。 */
    public record ProviderCredentialRequest(
            String provider,
            String apiKey) {
    }
}
