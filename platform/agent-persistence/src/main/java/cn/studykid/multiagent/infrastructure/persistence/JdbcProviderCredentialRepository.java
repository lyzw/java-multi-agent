package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.crypto.AesGcmCipher;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort;
import cn.studykid.multiagent.ports.modelcredential.ProviderCredentialPort.ProviderCredentialView;

/**
 * 供应商密钥仓储（全局表 {@code model_provider_credential}，<b>无租户、无 RLS</b>）。
 *
 * <p><b>⚠️ 为什么不用 {@code tenantConnection}</b>：密钥是平台级资源，
 * 由超级管理员在管理后台统一维护、所有租户共用，<b>不绑定 tenant_id</b>，
 * 表上没有任何 RLS 策略。故直接走 {@code dataSource.getConnection()}（与
 * {@code JdbcModelCatalogRepository} 同口径），不注入租户上下文。
 *
 * <p><b>⚠️ 加密落库</b>：{@code api_key} 明文仅驻留内存，落库前经
 * {@link AesGcmCipher} 加密；读回时解密仅用于内部网关解析与脱敏视图生成，
 * 管理后台响应只暴露脱敏串（末 4 位），<b>绝不回显明文</b>。
 */
@Repository
public class JdbcProviderCredentialRepository implements ProviderCredentialPort {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcProviderCredentialRepository.class);

    private final JdbcTemplate jdbc;
    private final String masterKey;
    private volatile AesGcmCipher cipher;

    /**
     * Spring 装配入口：主密钥经环境变量注入，派生为 AES-256 密钥。
     *
     * <p>⚠️ <b>必须显式 {@code @Autowired}</b>：本类有两个公开构造（另一个供测试注入
     * {@link AesGcmCipher}），组件扫描下多构造且无 {@code @Autowired} 时 Spring 不给
     * 候选构造、回落无参构造，启动会报 {@code No default constructor found}
     * （与 {@code JdbcResultQueryRepository} / {@code JdbcAgentQueryRepository} 同口径）。
     *
     * <p>⚠️ <b>延迟构造</b>：主密钥缺失时<b>不拒绝启动</b>（仅在该端点真正被调用、
     * 需要加/解密时才 fail-closed 抛出），避免本地开发未配置密钥时整个应用起不来。
     * 未配置密钥时本端点写入会返回 500（明确告知需先配置主密钥），属于正确行为。
     */
    @Autowired
    public JdbcProviderCredentialRepository(DataSource dataSource,
            @Value("${agent.model-credential.master-key:}") String masterKey) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    /** 核心构造（密文工具显式注入，便于测试）。 */
    public JdbcProviderCredentialRepository(DataSource dataSource, AesGcmCipher cipher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.masterKey = "";
        this.cipher = cipher;
    }

    /** 取加密工具（延迟构造并缓存；主密钥缺失时在操作点 fail-closed）。 */
    private @NonNull AesGcmCipher cipher() {
        AesGcmCipher c = cipher;
        if (c == null) {
            synchronized (this) {
                c = cipher;
                if (c == null) {
                    c = AesGcmCipher.fromMasterSecret(masterKey);
                    cipher = c;
                }
            }
        }
        return c;
    }

    @Override
    public @NonNull Optional<String> resolveApiKey(@NonNull String provider) {
        try {
            String encrypted = jdbc.queryForObject(
                    "SELECT encrypted_key FROM model_provider_credential WHERE provider = ?",
                    String.class, provider);
            if (encrypted == null) {
                return Optional.empty();
            }
            return Optional.of(cipher().decrypt(encrypted));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            // 解密失败（密钥轮换/损坏）→ 视为缺失，回落 env/Fake，绝不抛给调用方
            log.warn("供应商 {} 密钥解密失败，视为未配置: {}", provider, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(@NonNull String provider, @NonNull String apiKey) {
        String encrypted = cipher().encrypt(apiKey);
        String checksum = AesGcmCipher.checksum(apiKey);
        jdbc.update(
                "INSERT INTO model_provider_credential "
                        + "(provider, encrypted_key, key_checksum, algorithm) "
                        + "VALUES (?, ?, ?, 'AES_256_GCM') "
                        + "ON CONFLICT (provider) DO UPDATE SET "
                        + "encrypted_key = EXCLUDED.encrypted_key, "
                        + "key_checksum = EXCLUDED.key_checksum, "
                        + "algorithm = EXCLUDED.algorithm, "
                        + "updated_at = now()",
                provider, encrypted, checksum);
        log.info("供应商 {} 密钥已安全写入（AES-256-GCM，密文落库）", provider);
    }

    @Override
    public void delete(@NonNull String provider) {
        jdbc.update("DELETE FROM model_provider_credential WHERE provider = ?", provider);
        log.info("供应商 {} 密钥已删除", provider);
    }

    @Override
    public @NonNull List<ProviderCredentialView> listMasked() {
        List<ProviderCredentialView> result = new ArrayList<>();
        try (Connection c = jdbc.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT provider, encrypted_key, updated_at "
                             + "FROM model_provider_credential ORDER BY provider")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(toMaskedView(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取供应商密钥列表失败", e);
        }
        return result;
    }

    @Override
    public @NonNull Optional<ProviderCredentialView> masked(@NonNull String provider) {
        try (Connection c = jdbc.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT provider, encrypted_key, updated_at "
                             + "FROM model_provider_credential WHERE provider = ?")) {
            ps.setString(1, provider);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(toMaskedView(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取供应商密钥失败: " + provider, e);
        }
    }

    private @NonNull ProviderCredentialView toMaskedView(@NonNull ResultSet rs)
            throws SQLException {
        String provider = rs.getString("provider");
        String encrypted = rs.getString("encrypted_key");
        Timestamp ts = rs.getTimestamp("updated_at");
        @Nullable String plaintext = null;
        try {
            plaintext = cipher().decrypt(encrypted);
        } catch (RuntimeException e) {
            // 解密失败（密钥不匹配）→ 脱敏视图仍返回，但 markedKey 为 null（不泄漏）
            log.warn("供应商 {} 密钥解密失败（可能已轮换主密钥），脱敏视图标记为未配置", provider);
        }
        return new ProviderCredentialView(
                provider,
                ProviderCredentialView.mask(plaintext),
                ts == null ? null : ts.toInstant());
    }

    /** 仅用于单测：暴露明文校验（不对外）。 */
    @NonNull Optional<String> rawDecryptForTest(@NonNull String provider) {
        return resolveApiKey(provider);
    }

    @Override
    public String toString() {
        return "JdbcProviderCredentialRepository";
    }

    /** 诊断用：当前表内 provider 数量（不暴露任何密钥）。 */
    public int count() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM model_provider_credential", Integer.class);
        return n == null ? 0 : n;
    }
}
