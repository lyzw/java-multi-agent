package cn.studykid.multiagent.core.security;

/**
 * 主体作用域 —— 表达「平台运维身份」的维度，是替代 PG {@code BYPASSRLS} 连接的关键。
 *
 * <p>详见迁移方案 12 文 §2.2：现役「平台运维身份」由独立数据源 + {@code PlatformOpsSessionGuard}
 * 识别（非上下文字段）；迁移后由 {@code scope} 表达，且必须由认证入口唯一写入，
 * 租户态请求绝不可自设 {@code PLATFORM}。
 */
public enum Scope {

    /** 租户态：仅可见本租户数据（及平台哨兵桶）。绝大多数请求落在此值。 */
    TENANT,

    /** 平台运维态：替代 {@code BYPASSRLS} 连接，可见全域。仅限 {@code isPlatformOps()} 主体。 */
    PLATFORM
}
