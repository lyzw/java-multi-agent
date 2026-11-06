package cn.studykid.multiagent.infrastructure.persistence.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.orchestration.ResultContractProvider;
import cn.studykid.multiagent.ports.persistence.CapResultContractPort;

/**
 * {@link ResultContractProvider} 的 PostgreSQL 实现（多级 Agent v1.1 · FR-027 · MA-T16）。
 *
 * <p>⚠️ <b>只认「已发布的固定版本」</b>：引用 {@code id@version} 解析不到
 * 已发布的版本时返回 {@code null}，由调用方 fail-closed 拒绝这次委派。
 * 「取不到就当没有契约」是错的 —— 那等于让「契约写了但没生效」变成常态。
 *
 * <p>⚠️ <b>草稿不可用</b>：未发布（{@code published_at IS NULL}）的契约版本
 * 即使存在也不可被引用 —— 否则「先引用再发布」就成了绕过发布流程的后门。
 */
public class JdbcResultContractProvider implements ResultContractProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcResultContractProvider.class);

    /** 固定版本引用的分隔符（{@code id@version}）。 */
    private static final String REF_SEPARATOR = "@";

    private final CapResultContractPort contracts;

    public JdbcResultContractProvider(@NonNull CapResultContractPort contracts) {
        this.contracts = contracts;
    }

    @Override
    public @Nullable String schemaFor(@NonNull String tenantId, @NonNull String contractRef) {
        int separator = contractRef.lastIndexOf(REF_SEPARATOR);
        if (separator <= 0 || separator == contractRef.length() - 1) {
            // ⚠️ 引用格式非法 → 取不到 → 调用方 fail-closed
            log.warn("输出契约引用格式非法: ref={}", contractRef);
            return null;
        }
        String contractId = contractRef.substring(0, separator);
        String versionText = contractRef.substring(separator + 1);
        int version;
        try {
            version = Integer.parseInt(versionText.trim());
        } catch (NumberFormatException e) {
            // ⚠️ 含 latest 等漂移别名 → 取不到
            log.warn("输出契约引用版本非法: ref={}", contractRef);
            return null;
        }
        if (version <= 0) {
            log.warn("输出契约引用版本非法: ref={}", contractRef);
            return null;
        }
        try {
            return contracts.find(tenantId, contractId, version)
                    .filter(cn.studykid.multiagent.core.cap.CapResultContractDef::isPublished)
                    .map(cn.studykid.multiagent.core.cap.CapResultContractDef::schemaJson)
                    .orElse(null);
        } catch (RuntimeException e) {
            // ⚠️ 读不到 → 返回 null（调用方 fail-closed），不得抛出把 Run 带崩
            log.error("输出契约读取失败: tenant={}, ref={}", tenantId, contractRef, e);
            return null;
        }
    }
}
