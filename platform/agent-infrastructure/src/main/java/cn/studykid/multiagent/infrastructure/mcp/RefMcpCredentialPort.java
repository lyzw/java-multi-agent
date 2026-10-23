package cn.studykid.multiagent.infrastructure.mcp;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.mcp.McpCredentialPort;

/**
 * 按 {@code credential_ref} 定位凭据材料（W-PERM-P4-T6 · 06 §5「凭据」行）。
 *
 * <p>06 §5 原文：{@code credential_ref} 引用密钥管理；明文禁入库；日志脱敏；
 * 支持轮换（轮换后连接级重验）。
 *
 * <p>⚠️ <b>本类只做「定位」，不存材料</b>：
 * {@code credential_ref} 是定位符（形如 {@code env://NAME} / {@code prop://key}），
 * 材料在解析那一刻才从外部取，取到后只在内存里流转。
 * 数据库里始终只有定位符。
 *
 * <p>⚠️ <b>不缓存</b>：每次解析都重新取 —— 这是「轮换后连接级重验」的前提。
 * 缓存材料会让轮换在缓存 TTL 内不生效。
 *
 * <p>⚠️ <b>日志脱敏</b>：失败日志只打「引用形态」与「失败原因」，
 * <b>绝不</b>打材料；{@link McpCredential#toString()} 也覆写为脱敏形式。
 */
public class RefMcpCredentialPort implements McpCredentialPort {

    private static final Logger log = LoggerFactory.getLogger(RefMcpCredentialPort.class);

    /** 环境变量引用前缀。 */
    public static final String ENV_SCHEME = "env://";

    /** 配置属性引用前缀。 */
    public static final String PROPERTY_SCHEME = "prop://";

    private final Function<String, Optional<String>> envLookup;
    private final Function<String, Optional<String>> propertyLookup;

    public RefMcpCredentialPort(@NonNull Function<String, Optional<String>> envLookup,
                                @NonNull Function<String, Optional<String>> propertyLookup) {
        this.envLookup = envLookup;
        this.propertyLookup = propertyLookup;
    }

    @Override
    public @NonNull Optional<McpCredential> resolve(@Nullable String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            // 无凭据配置 → 不是错误（authKind=none 的 server 合法）
            return Optional.empty();
        }
        String ref = credentialRef.trim();
        String lower = ref.toLowerCase(Locale.ROOT);
        Optional<String> secret;
        if (lower.startsWith(ENV_SCHEME)) {
            secret = envLookup.apply(ref.substring(ENV_SCHEME.length()));
        } else if (lower.startsWith(PROPERTY_SCHEME)) {
            secret = propertyLookup.apply(ref.substring(PROPERTY_SCHEME.length()));
        } else {
            // ⚠️ 未知引用形态 → 不解析、不回退成「把引用本身当令牌」
            log.warn("MCP 凭据引用形态未知，拒绝解析: scheme={}",
                    lower.contains("://") ? lower.substring(0, lower.indexOf("://")) : "<none>");
            return Optional.empty();
        }
        if (secret.isEmpty() || secret.get().isBlank()) {
            // ⚠️ 读不到 → 不返回空凭据（那等于「无认证」），而是「不可用」
            log.warn("MCP 凭据解析失败（材料不存在或为空）: scheme={}",
                    lower.contains("://") ? lower.substring(0, lower.indexOf("://")) : "<none>");
            return Optional.empty();
        }
        return Optional.of(new McpCredential(McpCredential.Kind.TOKEN, secret.get()));
    }
}
