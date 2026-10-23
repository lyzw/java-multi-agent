package cn.studykid.multiagent.application.cap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;

/**
 * MCP Server 配置的<b>配置期</b>校验（W-PERM-P4-T2 · §4.2 · §6.5 · 06 §5 · 04 §3）。
 *
 * <p>退出门禁 1：「仅 http/sse 传输启用；{@code dynamicDiscovery} 强制 false（配置期拒），stdio 未启用」。
 *
 * <p>⚠️ <b>为什么这些必须在配置期拒，而不是运行期拒</b>：
 * <ul>
 *   <li>{@code dynamicDiscovery=true} 会让工具清单<b>在运行时漂移</b> ——
 *       固定清单与指纹校验（S5a/S5c）随即失效，动态发现等于绕开整个授权模型。
 *       这种配置<b>一旦发布就再也收不回来</b>（在途 Run 的指纹已按旧清单固化），
 *       所以只能在写入时拒。</li>
 *   <li>{@code stdio} 传输会<b>在服务端拉起子进程</b>，其安全面（可执行文件白名单、
 *       命令拼接、环境变量、工作目录）是 06 §5 另一整套要求（V1 非必做，N7）。
 *       V1 未实现这套管控就放开 stdio，等于开了个不受控的本地执行口。</li>
 * </ul>
 *
 * <p>⚠️ <b>DB 层已有 CHECK 约束兜底</b>（{@code cap_mcp_server_def_no_dynamic_ck}），
 * 但 CHECK 只能挡住 {@code dynamicDiscovery}，挡不住「endpoint 指向内网」「stdio」，
 * 且报错信息是数据库原文，对配置作者不可读。本类提供<b>可读、可定位</b>的校验，
 * DB CHECK 作为最后一道防线。
 */
public final class McpServerConfigValidator {

    private McpServerConfigValidator() {
    }

    /**
     * 一条配置违规。
     *
     * @param field   违规字段
     * @param message 违规说明（面向配置作者）
     */
    public record Violation(@NonNull String field, @NonNull String message) {
        @Override
        public String toString() {
            return field + ": " + message;
        }
    }

    /**
     * 校验一份 MCP Server 定义。
     *
     * @param def 待校验的定义
     * @return 违规列表；空列表表示通过
     */
    public static @NonNull List<Violation> validate(@NonNull CapMcpServerDef def) {
        List<Violation> violations = new ArrayList<>();

        // ── 传输：仅 http / sse ──────────────────────────────────────
        if (def.transport() == CapMcpServerDef.Transport.STDIO) {
            violations.add(new Violation("transport",
                    "stdio 传输在 V1 未启用（N7）：它会在服务端拉起子进程，"
                            + "其可执行文件白名单与命令拼接管控属另一套要求，"
                            + "未实现前不得启用。请改用 http 或 sse。"));
        }
        if (!def.transport().isSupportedInV1()) {
            violations.add(new Violation("transport",
                    "V1 仅支持 http / sse，当前为 " + def.transport()));
        }

        // ── 动态发现：强制 false ─────────────────────────────────────
        if (def.dynamicDiscovery()) {
            violations.add(new Violation("dynamicDiscovery",
                    "动态发现必须为 false（N4）：动态工具清单会绕过固定清单与闭包指纹校验"
                            + "（S5a/S5c），使已发布配置的授权边界在运行期被悄悄改写。"));
        }

        // ── endpoint 形态 ───────────────────────────────────────────
        String endpoint = def.endpoint();
        if (def.transport().isSupportedInV1()) {
            if (endpoint == null || endpoint.isBlank()) {
                violations.add(new Violation("endpoint",
                        "http / sse 传输必须配置 endpoint"));
            } else {
                violations.addAll(validateEndpoint(endpoint));
            }
        }
        if (def.transport() == CapMcpServerDef.Transport.STDIO
                && (def.command() == null || def.command().isBlank())) {
            violations.add(new Violation("command", "stdio 传输必须配置 command"));
        }

        // ── 凭据：仅引用 ─────────────────────────────────────────────
        // ⚠️ 这里<b>不</b>校验 credential_ref 的字符串形态：
        //    §4.2 / 06 §5 只要求「凭据仅引用、明文禁入库、永不入指纹/日志」，
        //    并未规定引用的具体格式。凭据的形态合法性由解析处判定 ——
        //    {@code RefMcpCredentialPort} 对无法识别的形态直接返回「不可用」，
        //    传输层据此 fail-closed（不发起请求）。
        //    在这里猜一个格式并拒写，只会把「引用长什么样」变成配置期障碍，
        //    却拦不住「把明文令牌伪装成引用」这种真正的泄漏手法。

        return List.copyOf(violations);
    }

    /**
     * 校验 endpoint 的静态形态（不触网；DNS 后的实际 IP 校验在传输层）。
     *
     * @param endpoint 端点 URL
     * @return 违规列表
     */
    public static @NonNull List<Violation> validateEndpoint(@NonNull String endpoint) {
        List<Violation> violations = new ArrayList<>();
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException e) {
            violations.add(new Violation("endpoint", "不是合法的 URL: " + e.getReason()));
            return List.copyOf(violations);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))) {
            violations.add(new Violation("endpoint",
                    "仅允许 http / https 端点，当前 scheme=" + scheme));
        }
        if (uri.getUserInfo() != null) {
            violations.add(new Violation("endpoint",
                    "端点 URL 不得携带用户信息（user:pass@）——凭据只能走 credential_ref"));
        }
        if (uri.getFragment() != null) {
            violations.add(new Violation("endpoint", "端点 URL 不得携带 fragment"));
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            violations.add(new Violation("endpoint", "端点 URL 缺少主机名"));
        }
        int port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            violations.add(new Violation("endpoint", "端口越界: " + port));
        }
        return List.copyOf(violations);
    }

    /**
     * 判定凭据字段是否为「引用」而非明文。
     *
     * <p>⚠️ 这是<b>形状</b>检查，不是安全保证：
     * 它挡不住「把明文令牌写成 {@code secret://abc}」这种伪装。
     * 真正的保证在<b>写入侧</b>：凭据材料由密钥管理服务持有，
     * 平台只保存定位符（06 §5「凭据」行）。
     *
     * @param ref 凭据引用
     * @return 是否形似引用
     */
    public static boolean looksLikeReference(@Nullable String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        int idx = ref.indexOf("://");
        if (idx <= 0) {
            return false;
        }
        String scheme = ref.substring(0, idx).toLowerCase(Locale.ROOT);
        return !scheme.isBlank() && ref.length() > idx + 3;
    }
}
