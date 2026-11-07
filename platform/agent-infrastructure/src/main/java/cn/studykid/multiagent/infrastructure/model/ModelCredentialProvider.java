package cn.studykid.multiagent.infrastructure.model;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort;

/**
 * 模型凭据解析器（按 provider 解析 API Key）。
 *
 * <p><b>⚠️ 这是「密钥」与「目录」的边界</b>：{@code ModelDefinition}（管理后台维护的目录）
 * <b>只存模型元数据，不含密钥</b>；API Key 的权威来源分两级——
 * <ol>
 *   <li><b>带外加密存储（优先）</b>：管理后台经
 *       {@code /api/admin/model-credentials} 写入的密钥，由
 *       {@link ProviderCredentialPort} 解密返回（AES-256-GCM，密文落库）；</li>
 *   <li><b>环境变量（回落）</b>：{@code agent.model.<provider>.api-key}，
 *       用于历史部署兼容与本地开发（无 DB 密钥时）。</li>
 * </ol>
 * 两级都缺失 → 返回 {@code null}（调用方据此回落占位网关）。
 *
 * <p>⚠️ <b>密钥绝不回显</b>：本类只返回明文供运行时网关使用，
 * 任何对外响应都必须经脱敏视图（见 {@code ProviderCredentialPort.ProviderCredentialView}）。
 */
@Service
public class ModelCredentialProvider {

    private final Environment environment;

    /**
     * 带外密钥端口（管理后台写入、加密存储）。
     * <b>required=false</b>：未装配时回落环境变量，保证无 DB 密钥也能启动。
     */
    @Autowired(required = false)
    @Nullable ProviderCredentialPort credentialPort;

    public ModelCredentialProvider(@NonNull Environment environment) {
        this.environment = environment;
    }

    /**
     * 取某 provider 的 API Key。
     *
     * <p><b>⚠️ DB 优先、env 回落</b>：管理后台集中维护的密钥优先于环境变量，
     * 实现「密钥统一由后台管理」的闭环；DB 缺失/解密失败再回落 env。
     */
    public @Nullable String apiKeyFor(@NonNull String provider) {
        if (credentialPort != null) {
            Optional<String> fromDb = credentialPort.resolveApiKey(provider);
            if (fromDb.isPresent() && !fromDb.get().isBlank()) {
                return fromDb.get();
            }
        }
        return environment.getProperty("agent.model." + provider + ".api-key");
    }

    /**
     * 取某 provider 的默认 base-url（目录未给 baseUrl 时的兜底）。
     *
     * <p>已知 provider 的出厂默认；未知 provider 返回 {@code null}，
     * 由调用方要求目录里必须显式给出 baseUrl（fail-closed：未知 provider 不臆造地址）。
     */
    public @Nullable String defaultBaseUrlFor(@NonNull String provider) {
        String configured = environment.getProperty("agent.model." + provider + ".base-url");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return switch (provider) {
            case "minimax" -> "https://api.minimax.chat/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "workbuddy" -> "https://api.workbuddy.ai/v1";
            default -> null;
        };
    }
}
