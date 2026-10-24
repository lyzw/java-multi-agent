package cn.studykid.rag.management.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.DictionaryPort.DictEntry;

/**
 * 字典的 JDBC 实现（Point 2）。
 *
 * <p><b>⚠️ 租户隔离铁律</b>：与 {@link JdbcCorpusManagementRepository} 完全一致——
 * 每个方法都在 {@link TransactionTemplate} 内执行
 * 「{@code set_config('app.tenant_id', ?, true)} → 业务 SQL」，确保租户上下文与
 * 业务 SQL 落在同一事务、同一条连接，RLS 策略对本次读写生效。
 *
 * <p><b>⚠️ 动态 vs 派生</b>：{@link DictionaryPort#TYPE_SOURCE_TYPE} 走
 * {@code rag_dictionary} 表（租户隔离、可维护）；其余类型由 Java 枚举派生（只读），
 * 不触碰数据库，也不受租户隔离影响（枚举取值对所有租户一致）。
 */
public class JdbcDictionaryRepository implements DictionaryPort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 派生字典（只读）：类型 → 有序条目。标签为后端权威源，
     * 与前端 {@code RAG_*_LABELS} 同源，但不依赖前端。
     */
    private static final Map<String, List<DictEntry>> DERIVED = buildDerived();

    public JdbcDictionaryRepository(@NonNull JdbcTemplate jdbcTemplate,
                                    @NonNull TransactionTemplate transactionTemplate) {
        if (jdbcTemplate == null || transactionTemplate == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public boolean isEditable(@NonNull String type) {
        return TYPE_SOURCE_TYPE.equals(type);
    }

    @Override
    @NonNull
    public List<DictEntry> entriesOf(@NonNull RagTenantContext context, @NonNull String type) {
        if (DERIVED.containsKey(type)) {
            return DERIVED.get(type);
        }
        // 动态类型：从 rag_dictionary 读取（RLS 已按租户过滤）
        return transactionTemplate.execute(status -> {
            setTenant(context.tenantId());
            return jdbcTemplate.query(
                    """
                    SELECT code, label, sort_order, enabled
                    FROM rag_dictionary
                    WHERE tenant_id = rag_current_tenant() AND dict_type = ?
                    ORDER BY sort_order, code
                    """,
                    (rs, i) -> new DictEntry(
                            type,
                            rs.getString("code"),
                            rs.getString("label"),
                            rs.getInt("sort_order"),
                            rs.getBoolean("enabled")),
                    type);
        });
    }

    @Override
    public void upsertEntry(@NonNull RagTenantContext context, @NonNull DictEntry entry) {
        if (!isEditable(entry.type())) {
            throw new IllegalStateException("字典类型不可维护：" + entry.type());
        }
        transactionTemplate.executeWithoutResult(status -> {
            setTenant(context.tenantId());
            jdbcTemplate.update("""
                    INSERT INTO rag_dictionary (tenant_id, dict_type, code, label, sort_order, enabled)
                    VALUES (rag_current_tenant(), ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, dict_type, code)
                    DO UPDATE SET label = EXCLUDED.label,
                                  sort_order = EXCLUDED.sort_order,
                                  enabled = EXCLUDED.enabled
                    """,
                    entry.type(), entry.code(), entry.label(),
                    entry.sortOrder(), entry.enabled());
        });
    }

    @Override
    public void deleteEntry(@NonNull RagTenantContext context, @NonNull String type, @NonNull String code) {
        if (!isEditable(type)) {
            throw new IllegalStateException("字典类型不可维护：" + type);
        }
        transactionTemplate.executeWithoutResult(status -> {
            setTenant(context.tenantId());
            jdbcTemplate.update(
                    """
                    DELETE FROM rag_dictionary
                    WHERE tenant_id = rag_current_tenant() AND dict_type = ? AND code = ?
                    """,
                    type, code);
        });
    }

    /**
     * 设置租户会话变量（与 V1 同构）。
     *
     * <p>⚠️ {@code set_config(..., true)} 是「仅在当前事务有效」，必须放在
     * 业务 SQL 之前，且整段包在 {@link TransactionTemplate} 内。
     */
    private void setTenant(@NonNull String tenantId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
    }

    private static Map<String, List<DictEntry>> buildDerived() {
        Map<String, List<DictEntry>> map = new LinkedHashMap<>();

        Map<String, String> tierLabels = Map.of(
                "OFFICIAL_PUBLISHER", "教材出版社官方",
                "OFFICIAL_TUTORIAL", "教辅品牌正式出版",
                "PEER_REVIEWED_PAPER", "同行评审论文",
                "PREPRINT", "预印本 / 白皮书",
                "UNVERIFIED", "未校验");
        List<DictEntry> tiers = new ArrayList<>();
        for (TrustTier t : TrustTier.values()) {
            tiers.add(new DictEntry(TYPE_TRUST_TIER, t.name(),
                    tierLabels.getOrDefault(t.name(), t.name()), 0, true));
        }
        map.put(TYPE_TRUST_TIER, tiers);

        Map<String, String> methodLabels = Map.of(
                "NONE", "无",
                "ISBN", "ISBN 校验",
                "DOI", "DOI 校验",
                "MANUAL_REVIEW", "人工逐份比对",
                "OFFICIAL_HASH", "官方哈希比对");
        List<DictEntry> methods = new ArrayList<>();
        for (VerificationMethod m : VerificationMethod.values()) {
            methods.add(new DictEntry(TYPE_VERIFICATION_METHOD, m.name(),
                    methodLabels.getOrDefault(m.name(), m.name()), 0, true));
        }
        map.put(TYPE_VERIFICATION_METHOD, methods);

        Map<String, String> licenseLabels = Map.of(
                "LICENSED", "已获授权",
                "FAIR_USE_QUOTATION", "合理引用",
                "PENDING", "未取得授权",
                "RESTRICTED", "禁止使用");
        List<DictEntry> licenses = new ArrayList<>();
        for (Map.Entry<String, String> e : licenseLabels.entrySet()) {
            licenses.add(new DictEntry(TYPE_LICENSE_STATUS, e.getKey(), e.getValue(), 0, true));
        }
        map.put(TYPE_LICENSE_STATUS, licenses);

        return map;
    }
}
