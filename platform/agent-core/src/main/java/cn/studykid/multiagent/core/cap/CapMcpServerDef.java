package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * MCP Server 定义版本行（§6.1 {@code cap_mcp_server_def} · §4.2 / §6.5）。
 *
 * <p>platform 级对象（<b>不启用 RLS</b>）。
 *
 * <p>⚠️ <b>凭据仅存引用</b>：{@code credentialRef} 只是凭据库中的定位符，
 * <b>凭据材料永不入库、永不入指纹、永不入日志</b>（§4.2 / §6.4）。
 * 本字段<b>不参与</b>闭包指纹计算 —— 否则凭据轮换会让所有在途 Run 集体失效，
 * 且会把凭据库定位符泄漏进指纹。
 */
public record CapMcpServerDef(
        @NonNull String id,
        int version,
        @NonNull Transport transport,
        @Nullable String endpoint,
        @Nullable String command,
        @NonNull AuthKind authKind,
        @NonNull McpTrustTier trustTier,
        @Nullable List<String> exposedToolAllowlist,
        @NonNull CapabilityRiskLevel connectionRisk,
        boolean dynamicDiscovery,
        @NonNull String configFingerprint,
        @Nullable String credentialRef,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt) {

    /** 传输方式。V1 <b>仅支持</b> {@link #HTTP} 与 {@link #SSE}（§4.2）。 */
    public enum Transport {
        /** 本地子进程（V1 不支持，仅为容纳 DB 取值域而存在）。 */
        STDIO,
        /** HTTP。 */
        HTTP,
        /** SSE。 */
        SSE;

        /**
         * @param raw 持久化文本
         * @return 解析结果；非法值返回 {@code null}
         */
        public static @Nullable Transport parse(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
            for (Transport candidate : values()) {
                if (candidate.name().equals(normalized)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * @return 是否为 V1 支持的传输（http / sse）
         */
        public boolean isSupportedInV1() {
            return this == HTTP || this == SSE;
        }
    }

    /** 认证方式。 */
    public enum AuthKind {
        /** 令牌。 */
        TOKEN,
        /** OAuth。 */
        OAUTH,
        /** 无认证。 */
        NONE;

        /**
         * @param raw 持久化文本
         * @return 解析结果；非法值返回 {@code null}
         */
        public static @Nullable AuthKind parse(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
            for (AuthKind candidate : values()) {
                if (candidate.name().equals(normalized)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * @return 是否为已发布版本行
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * @return 暴露的操作清单是否<b>显式为空</b>（{@code []}）；
     *         {@code null} 表示未配置 —— 二者语义不同（§4.4）
     */
    public boolean hasEmptyAllowlist() {
        return exposedToolAllowlist != null && exposedToolAllowlist.isEmpty();
    }

    /**
     * <b>脱敏</b>描述：{@code credentialRef} 不出现在字符串形式里。
     *
     * <p>⚠️ <b>为什么必须覆写</b>：
     * {@code record} 自动生成的 {@code toString()} 会把<b>所有</b>字段拼进去。
     * 只要这个对象被日志框架、异常消息或 {@code Map.toString()} 带出来，
     * 凭据定位符就跟着进了日志 —— 而定位符往往指向一个真实凭据。
     * 记录被打印是最常见的泄漏路径，因此在这里就把它堵死。
     *
     * <p>⚠️ 注意：<b>凭据材料</b>本来就不在这个对象里（只有定位符），
     * 但定位符也不该随记录到处走。
     *
     * @return 脱敏后的描述
     */
    @Override
    public String toString() {
        return "CapMcpServerDef[id=" + id + ", version=" + version
                + ", transport=" + transport
                + ", endpoint=" + endpoint
                + ", authKind=" + authKind
                + ", trustTier=" + trustTier
                + ", exposedToolAllowlist=" + exposedToolAllowlist
                + ", connectionRisk=" + connectionRisk
                + ", dynamicDiscovery=" + dynamicDiscovery
                + ", configFingerprint=" + configFingerprint
                + ", credentialRef=" + (credentialRef == null ? "null" : "<redacted>")
                + ", schemaVersion=" + schemaVersion
                + ", createdAt=" + createdAt
                + ", publishedAt=" + publishedAt + "]";
    }
}
