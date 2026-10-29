package cn.studykid.rag.core.security;

import org.jspecify.annotations.Nullable;

/**
 * 鉴权过滤器与业务端点之间的<b>调用方身份</b>约定（评审 P0-2 的延伸）。
 *
 * <p><b>为什么需要这个类</b>：{@code RagJwtAuthFilter} 验签成功后，
 * 过去只把身份写进日志上下文就丢弃了「这个调用方属于哪个令牌域」。
 * 后果是业务端点无从区分「租户令牌在查自己」与「平台令牌在跨租户治理」，
 * 于是<b>平台级作用域</b>（看/管全局语料）没有任何闸门可挂——
 * 任何登录进控制台的账号都能改任意租户语料，而这恰恰是无法审计的。
 * 本类把「域」变成一个显式、只由过滤器写入的 request attribute。
 *
 * <p><b>⚠️ 写入方唯一</b>：只有 {@code RagJwtAuthFilter} 在<b>验签通过之后</b>写这些属性。
 * 端点侧读到 {@link #DOMAIN_UNVERIFIED} 或缺失时<b>必须</b>按未认证处理，
 * 绝不能因为「属性是调用方可控的」而放行——它是 attribute 不是 header，
 * 但逃生门（{@code rag.security.enabled=false}）下它是唯一的值，
 * 所以「平台级作用域在逃生门下不可用」正是这里要守住的纪律。
 */
public final class RagCallerAttributes {

    /** 请求属性名：调用方令牌域（取值为下面三个常量之一）。 */
    public static final String DOMAIN_ATTRIBUTE = "rag.caller.domain";

    /** 请求属性名：验签通过的主体 ID（{@code sub} claim）。 */
    public static final String PRINCIPAL_ATTRIBUTE = "rag.caller.principal";

    /**
     * 请求属性名：治理票据声明的作用域（{@code rag_scope} claim）。
     *
     * <p><b>为什么单独一个属性而不是复用 {@link #DOMAIN_ATTRIBUTE}</b>：
     * 「是平台域」与「被授权动全局语料」是两件事。平台控制台<b>登录</b>令牌
     * 已经是平台域（能读自己的控制台），但任何平台域账号都能改全局语料
     * 就是把「控制台账号」等同于「语料治理权」——
     * 而全局语料影响的是<b>所有租户</b>的检索结果。
     * 所以票据必须是一张<b>单独的短 TTL 令牌</b>，由平台在权限闸门之后签发，
     * claim 里写明授权到哪一档（第四刀 §1.2）。
     *
     * <p>⚠️ 缺该属性 = 无票据（不是「票据内容为空字符串」）。
     * 端点侧据此拒绝，且<b>绝不</b>回退去看请求头里自报的票据值。
     */
    public static final String TICKET_SCOPE_ATTRIBUTE = "rag.caller.ticket.scope";

    /** 租户域令牌（{@code aud=agent-platform}）：已强制与 {@code X-Tenant-Id} 归属绑定。 */
    public static final String DOMAIN_TENANT = "tenant";

    /** 平台域令牌（{@code aud=admin-console-api}）：跨租户治理属设计内行为。 */
    public static final String DOMAIN_PLATFORM = "platform";

    /**
     * 鉴权逃生门下的调用方（{@code rag.security.enabled=false}）。
     *
     * <p>这不是「已验证的身份」，只是「本机开发放行」，
     * 因此<b>平台级作用域一律不可用</b>——否则本地配置错误会顺带打开一个跨租户入口。
     */
    public static final String DOMAIN_UNVERIFIED = "unverified";

    private RagCallerAttributes() {
    }

    /**
     * 读取请求属性里的令牌域；缺失时按 {@link #DOMAIN_UNVERIFIED} 处理（fail-closed）。
     *
     * @param raw 属性原始值（可为 null）
     * @return 三个域常量之一
     */
    public static String normalizeDomain(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : DOMAIN_UNVERIFIED;
    }
}
