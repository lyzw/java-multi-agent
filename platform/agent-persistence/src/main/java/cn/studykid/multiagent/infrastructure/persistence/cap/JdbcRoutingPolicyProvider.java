package cn.studykid.multiagent.infrastructure.persistence.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.ports.orchestration.RoutingPolicyProvider;
import cn.studykid.multiagent.ports.persistence.CapRoutingPolicyPort;

/**
 * {@link RoutingPolicyProvider} 的 PostgreSQL 实现（多级 Agent v1.1 · 第十四批）。
 *
 * <p>⚠️ <b>只认「已发布的固定版本」</b>：引用 {@code id@version} 解析不到
 * 已发布的版本时返回 {@code null}，由调用方 fail-closed 拒绝这次委派。
 * 「取不到就退回父声明的子 Agent」是错的 —— 那等于让
 * 「策略写了但没生效」变成常态，且外部观察不到任何差异。
 *
 * <p>⚠️ <b>草稿不可用</b>：未发布（{@code published_at IS NULL}）的策略版本
 * 即使存在也不可被引用 —— 否则「先引用再发布」就成了绕过发布流程的后门。
 * 与输出契约端口同口径。
 */
public class JdbcRoutingPolicyProvider implements RoutingPolicyProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcRoutingPolicyProvider.class);

    /** 固定版本引用的分隔符（{@code id@version}）。 */
    private static final String REF_SEPARATOR = "@";

    private final CapRoutingPolicyPort policies;

    public JdbcRoutingPolicyProvider(@NonNull CapRoutingPolicyPort policies) {
        this.policies = policies;
    }

    @Override
    public @Nullable CapRoutingPolicyDef policyFor(@NonNull String tenantId,
                                                   @NonNull String policyRef) {
        int separator = policyRef.lastIndexOf(REF_SEPARATOR);
        if (separator <= 0 || separator == policyRef.length() - 1) {
            // ⚠️ 引用格式非法 → 取不到 → 调用方 fail-closed
            log.warn("路由策略引用格式非法: ref={}", policyRef);
            return null;
        }
        String policyId = policyRef.substring(0, separator);
        String versionText = policyRef.substring(separator + 1);
        int version;
        try {
            version = Integer.parseInt(versionText.trim());
        } catch (NumberFormatException e) {
            // ⚠️ 含 latest 等漂移别名 → 取不到
            log.warn("路由策略引用版本非法: ref={}", policyRef);
            return null;
        }
        if (version <= 0) {
            log.warn("路由策略引用版本非法: ref={}", policyRef);
            return null;
        }
        try {
            return policies.find(tenantId, policyId, version)
                    .filter(CapRoutingPolicyDef::isPublished)
                    .orElse(null);
        } catch (RuntimeException e) {
            // ⚠️ 读不到 → 返回 null（调用方 fail-closed），不得抛出把 Run 带崩
            log.error("路由策略读取失败: tenant={}, ref={}", tenantId, policyRef, e);
            return null;
        }
    }
}
