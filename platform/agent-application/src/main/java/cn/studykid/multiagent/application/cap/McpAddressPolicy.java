package cn.studykid.multiagent.application.cap;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.McpTrustTier;

/**
 * MCP 端点的地址安全策略（W-PERM-P4-T5 · §6.5 · 06 §5「SSRF 防护（端点）」）。
 *
 * <p>06 §5 原文：
 * <blockquote>
 * {@code endpoint} 须落允许范围（内网白名单/域名清单）；禁止 {@code 127.0.0.0/8}、
 * {@code 169.254.0.0/16}、私有 CIDR，<b>除非 {@code trustTier=internal} 显式授权</b>。
 * </blockquote>
 *
 * <p>⚠️ <b>为什么按「解析后的实际 IP」判定，而不是按域名或配置文本判定</b>：
 * 域名可以随时被改指（DNS rebinding），配置文本里的域名看起来再正常，
 * 解析出来可能落在 {@code 169.254.169.254}（云元数据）。
 * <b>唯一可信的判定对象是 IP</b>。
 *
 * <p>⚠️ <b>云元数据地址在任何信任级别下都绝对禁止</b>（本类的刻意收紧）：
 * 06 §5 的「除非 trustTier=internal 显式授权」在字面上也覆盖了 {@code 169.254.0.0/16}，
 * 但 {@code 169.254.169.254} 是云厂商的实例元数据服务 ——
 * 能读到它就等于读走了实例角色的临时凭据，进而横向移动到整个云账号。
 * <b>没有任何业务理由需要 MCP 去访问元数据地址</b>，
 * 因此本类把它从「可豁免」提升为「绝对禁止」。
 *
 * <p>⚠️ <b>本类只做判定，不做连接</b>：
 * 判定与使用之间存在时间窗（TOCTOU），防 rebinding 的另一半在
 * {@code McpEndpointGuard}（解析→连接用同一结果 + 连接前复验）与传输层。
 */
public final class McpAddressPolicy {

    /** AWS / GCP / Azure 实例元数据服务地址（落在 link-local 段内）。 */
    public static final String METADATA_IPV4 = "169.254.169.254";

    /** AWS ECS 任务凭据端点（落在 link-local 段内）。 */
    public static final String ECS_CREDENTIALS_IPV4 = "169.254.170.2";

    /** AWS EC2 IPv6 元数据地址。 */
    public static final String METADATA_IPV6 = "fd00:ec2::254";

    /** 阿里云元数据服务地址（落在 CGNAT 段内）。 */
    public static final String ALIYUN_METADATA_IPV4 = "100.100.100.200";

    private McpAddressPolicy() {
    }

    /**
     * 判定一个地址是否允许作为 MCP 端点目标。
     *
     * @param address 已解析的地址
     * @param tier    MCP Server 的信任级别（{@code INTERNAL} 才可显式授权内网地址）
     * @return 是否允许
     */
    public static boolean isAllowed(@NonNull InetAddress address, @NonNull McpTrustTier tier) {
        return forbiddenReason(address, tier) == null;
    }

    /**
     * 判定一个地址是否<b>绝对禁止</b>（任何信任级别都不放行）。
     *
     * <p>用于云元数据地址等「无论配置成什么都不该访问」的目标。
     *
     * @param address 已解析的地址
     * @return 是否绝对禁止
     */
    public static boolean isAbsolutelyForbidden(@NonNull InetAddress address) {
        return absoluteReason(address) != null;
    }

    /**
     * 求出地址被禁止的原因。
     *
     * @param address 已解析的地址
     * @param tier    信任级别
     * @return 禁止原因；{@code null} 表示允许
     */
    public static @Nullable String forbiddenReason(@NonNull InetAddress address,
                                                   @NonNull McpTrustTier tier) {
        String absolute = absoluteReason(address);
        if (absolute != null) {
            return absolute;
        }
        boolean internalAuthorized = tier == McpTrustTier.INTERNAL;
        if (address.isAnyLocalAddress()) {
            return "地址为通配地址（0.0.0.0 / ::）";
        }
        if (address.isMulticastAddress()) {
            return "地址为多播地址";
        }
        if (address.isLoopbackAddress()) {
            return internalAuthorized ? null : "地址为回环地址（127.0.0.0/8），"
                    + "仅 trustTier=internal 可显式授权";
        }
        if (address.isLinkLocalAddress()) {
            // link-local（含 169.254.0.0/16）已在 absoluteReason 中绝对禁止
            return "地址为链路本地地址";
        }
        if (address.isSiteLocalAddress()) {
            return internalAuthorized ? null : "地址为私有网段（site-local），"
                    + "仅 trustTier=internal 可显式授权";
        }
        if (isUniqueLocal(address)) {
            return internalAuthorized ? null : "地址为唯一本地地址（fc00::/7），"
                    + "仅 trustTier=internal 可显式授权";
        }
        if (isCarrierGradeNat(address)) {
            return internalAuthorized ? null : "地址为运营商级 NAT 段（100.64.0.0/10），"
                    + "仅 trustTier=internal 可显式授权";
        }
        if (isBenchmark(address)) {
            return "地址为基准测试保留段（198.18.0.0/15）";
        }
        if (isDocumentation(address)) {
            return "地址为文档保留段";
        }
        if (isReserved(address)) {
            return "地址为保留段（240.0.0.0/4）";
        }
        if (isBroadcast(address)) {
            return "地址为广播地址";
        }
        return null;
    }

    /**
     * 绝对禁止的原因（与信任级别无关）。
     *
     * @param address 已解析的地址
     * @return 原因；{@code null} 表示不属绝对禁止
     */
    private static @Nullable String absoluteReason(@NonNull InetAddress address) {
        String metadata = metadataAddressOf(address);
        if (metadata != null) {
            return "目标是云元数据服务地址（" + metadata + "），任何信任级别都禁止访问";
        }
        if (address.isLinkLocalAddress()) {
            // 169.254.0.0/16 / fe80::/10 —— 含全部主流云元数据端点
            return "地址为链路本地地址（169.254.0.0/16 / fe80::/10），"
                    + "含云元数据服务，任何信任级别都禁止访问";
        }
        if (address.isMulticastAddress()) {
            return "地址为多播地址";
        }
        if (address.isAnyLocalAddress()) {
            return "地址为通配地址（0.0.0.0 / ::）";
        }
        return null;
    }

    /**
     * 判定是否命中已知的云元数据端点。
     *
     * <p>⚠️ <b>按字节比对，不按字面量文本比对</b>：
     * {@link InetAddress#getHostAddress()} 对 IPv6 会输出<b>展开</b>形式
     * （{@code fd00:ec2::254} → {@code fd00:ec2:0:0:0:0:0:254}），
     * 字符串相等在这里<b>不成立</b> —— 那是「看着做了检查、实际永远不触发」的假检查。
     *
     * @param address 地址
     * @return 命中的元数据端点描述；未命中为 {@code null}
     */
    private static @Nullable String metadataAddressOf(@NonNull InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            if (matches(bytes, 169, 254, 169, 254)) {
                return METADATA_IPV4;
            }
            if (matches(bytes, 169, 254, 170, 2)) {
                return ECS_CREDENTIALS_IPV4;
            }
            if (matches(bytes, 100, 100, 100, 200)) {
                return ALIYUN_METADATA_IPV4;
            }
            return null;
        }
        if (bytes.length == 16) {
            // fd00:ec2::254
            // ⚠️ 「ec2」是 <b>一个</b> 16 位组 = 0x0ec2（两个字节 0x0e 0xc2），
            //    写成 0x00, 0xec 会永远匹配不上 —— 那是「看着做了检查、实际不触发」。
            byte[] expected = new byte[16];
            expected[0] = (byte) 0xfd;
            expected[1] = 0x00;
            expected[2] = 0x0e;
            expected[3] = (byte) 0xc2;
            expected[14] = 0x02;
            expected[15] = (byte) 0x54;
            if (java.util.Arrays.equals(bytes, expected)) {
                return METADATA_IPV6;
            }
        }
        return null;
    }

    private static boolean matches(@NonNull byte[] bytes, int a, int b, int c, int d) {
        return (bytes[0] & 0xff) == a && (bytes[1] & 0xff) == b
                && (bytes[2] & 0xff) == c && (bytes[3] & 0xff) == d;
    }

    /**
     * 取地址的字面量文本（IPv6 去掉压缩写法差异）。
     *
     * @param address 地址
     * @return 字面量
     */
    public static @NonNull String literalOf(@NonNull InetAddress address) {
        return address.getHostAddress();
    }

    /**
     * 判断字面量是否为 IPv4-mapped IPv6（{@code ::ffff:127.0.0.1}）。
     *
     * <p>⚠️ 这类地址<b>必须</b>按内层 IPv4 判定：
     * 否则 {@code ::ffff:127.0.0.1} 会绕过回环地址检查。
     *
     * @param address 地址
     * @return 是否为 IPv4-mapped
     */
    public static boolean isIpv4Mapped(@NonNull InetAddress address) {
        if (!(address instanceof Inet6Address v6)) {
            return false;
        }
        return v6.isIPv4CompatibleAddress() || isMappedBytes(v6);
    }

    private static boolean isMappedBytes(@NonNull Inet6Address v6) {
        byte[] bytes = v6.getAddress();
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        // 前 10 字节为 0，第 11、12 字节为 ff:ff → ::ffff:a.b.c.d
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    /**
     * 剥离 IPv4-mapped 包装，得到内层 IPv4 地址。
     *
     * @param address 地址
     * @return 内层 IPv4 地址；非映射时返回原地址
     */
    public static @NonNull InetAddress unwrapIpv4Mapped(@NonNull InetAddress address) {
        if (!isIpv4Mapped(address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        byte[] v4 = new byte[4];
        System.arraycopy(bytes, 12, v4, 0, 4);
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            return address;
        }
    }

    // ── 段判定 ──────────────────────────────────────────────────────

    private static boolean isUniqueLocal(@NonNull InetAddress address) {
        if (!(address instanceof Inet6Address v6)) {
            return false;
        }
        byte[] b = v6.getAddress();
        return (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierGradeNat(@NonNull InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address && b.length == 4) {
            return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        }
        return false;
    }

    private static boolean isBenchmark(@NonNull InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address && b.length == 4) {
            return (b[0] & 0xff) == 198 && (b[1] & 0xff) >= 18 && (b[1] & 0xff) <= 19;
        }
        return false;
    }

    private static boolean isDocumentation(@NonNull InetAddress address) {
        byte[] b = address.getAddress();
        if (!(address instanceof Inet4Address) || b.length != 4) {
            return false;
        }
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        int d = b[2] & 0xff;
        return (a == 192 && c == 0 && d == 2)
                || (a == 198 && c == 51 && d == 100)
                || (a == 203 && c == 0 && d == 113);
    }

    private static boolean isReserved(@NonNull InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address && b.length == 4) {
            return (b[0] & 0xf0) == 240;
        }
        return false;
    }

    private static boolean isBroadcast(@NonNull InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address && b.length == 4) {
            return (b[0] & 0xff) == 255 && (b[1] & 0xff) == 255
                    && (b[2] & 0xff) == 255 && (b[3] & 0xff) == 255;
        }
        return false;
    }
}
