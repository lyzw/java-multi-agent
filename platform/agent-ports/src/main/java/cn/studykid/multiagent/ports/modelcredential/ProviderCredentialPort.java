package cn.studykid.multiagent.ports.modelcredential;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 供应商密钥端口（带外加密存储）。
 *
 * <p><b>职责边界</b>：模型目录（{@code ModelCatalogPort}）<b>只存配置、不存密钥</b>；
 * 本端口专管「按 provider 的 API Key」，由各 provider 适配的网关在运行时解析。
 *
 * <p><b>⚠️ 加密与脱敏</b>：
 * <ul>
 *   <li>明文密钥<b>只存在于运行时内存</b>，落库前由实现层做 AES-256-GCM 加密；</li>
 *   <li>{@code listMasked()}/{@code masked()} 返回<b>脱敏视图</b>（仅末 4 位），
 *       管理后台响应绝不携带明文，防止密钥经 API 泄漏；</li>
 *   <li>明文仅在 {@code resolveApiKey()} 内部解析，供网关调用，不外泄。</li>
 * </ul>
 *
 * <p><b>⚠️ 全局表、无租户</b>：密钥是平台级资源，由超级管理员统一维护，
 * 不绑定 tenant_id，实现层直接走 {@code dataSource.getConnection()}（与
 * {@code JdbcModelCatalogRepository} 同口径），不注入租户上下文。
 */
public interface ProviderCredentialPort {

    /**
     * 内部解析：返回某 provider 的明文 API Key（仅运行时网关使用）。
     * 未配置 / 解密失败返回 {@code empty}（调用方据此回落环境变量或 Fake 网关）。
     */
    @NonNull Optional<String> resolveApiKey(@NonNull String provider);

    /** 明文写入（覆盖同一 provider；幂等 upsert）。 */
    void save(@NonNull String provider, @NonNull String apiKey);

    /** 删除某 provider 的密钥（不存在则静默）。 */
    void delete(@NonNull String provider);

    /** 管理后台脱敏视图（全量，不返回明文）。 */
    @NonNull List<ProviderCredentialView> listMasked();

    /** 单条脱敏视图（不返回明文）。 */
    @NonNull Optional<ProviderCredentialView> masked(@NonNull String provider);

    /** 脱敏视图（管理后台只读，绝不携带明文）。 */
    record ProviderCredentialView(
            @NonNull String provider,
            @Nullable String maskedKey,
            @Nullable Instant updatedAt) {

        /** 由明文密钥生成脱敏串：末 4 位可见，其余以 **** 遮蔽。 */
        public static @Nullable String mask(@Nullable String plaintext) {
            if (plaintext == null || plaintext.isEmpty()) {
                return null;
            }
            if (plaintext.length() <= 4) {
                return "****";
            }
            return "****" + plaintext.substring(plaintext.length() - 4);
        }
    }
}
