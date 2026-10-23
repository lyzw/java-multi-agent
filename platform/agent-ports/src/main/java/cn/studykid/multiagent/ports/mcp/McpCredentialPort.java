package cn.studykid.multiagent.ports.mcp;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * MCP 凭据解析端口（§4.2 · §6.5 · 06 §5「凭据」行）。
 *
 * <p>06 §5 原文：{@code credential_ref} 引用密钥管理；明文禁入库；日志脱敏；
 * <b>支持轮换（轮换后连接级重验）</b>。
 *
 * <p>⚠️ <b>本端口是「凭据材料」进入进程的唯一入口</b>：
 * 材料一旦到手就只存在于内存里，<b>不得</b>写回数据库、不得进指纹、不得进日志。
 * 实现方必须保证：
 * <ul>
 *   <li>解析结果<b>不缓存</b>（或缓存键含凭据版本），否则轮换不生效；
 *       06 §5 明确要求「轮换后连接级重验」。</li>
 *   <li>失败时返回 {@link Optional#empty()}，<b>不抛异常带出材料</b>。</li>
 * </ul>
 */
public interface McpCredentialPort {

    /**
     * 按引用解析凭据。
     *
     * @param credentialRef 凭据引用（{@code credential_ref}）
     * @return 凭据；引用为空或解析不到时为 {@link Optional#empty()}
     */
    @NonNull Optional<McpCredential> resolve(@Nullable String credentialRef);

    /**
     * 一份已解析的凭据。
     *
     * <p>⚠️ <b>{@code secret} 永不出现在 {@code toString()} 里</b>：
     * 记录被日志框架或异常消息打印时，{@code toString()} 是最常见的泄漏路径。
     * 这里显式覆写为「只显示类型与引用」。
     *
     * @param kind   凭据类型
     * @param secret 凭据材料（内存态，禁止外传）
     */
    record McpCredential(@NonNull Kind kind, @Nullable String secret) {

        /** 凭据类型。 */
        public enum Kind {
            /** 令牌。 */
            TOKEN,
            /** OAuth 访问令牌。 */
            OAUTH,
            /** 无认证。 */
            NONE
        }

        public McpCredential {
            if (kind == null) {
                throw new IllegalArgumentException("凭据类型不能为空");
            }
        }

        /**
         * @return <b>脱敏</b>描述：只暴露类型，绝不暴露材料
         */
        @Override
        public String toString() {
            return "McpCredential[kind=" + kind + ", secret=<redacted>]";
        }
    }
}
