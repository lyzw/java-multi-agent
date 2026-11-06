package cn.studykid.rag.management.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.core.source.SourceVerification.VerificationMethod;
import cn.studykid.rag.core.source.TrustTier;
import cn.studykid.rag.management.core.RagManagementContext;
import cn.studykid.rag.management.core.RagTenantContext;
import cn.studykid.rag.management.ports.DictionaryPort;
import cn.studykid.rag.management.ports.DictionaryPort.DictEntry;
import cn.studykid.rag.management.ports.DictionaryPort.Scope;

/**
 * 字典的 JDBC 实现（Point 2）。
 *
 * <p><b>⚠️ 租户隔离铁律</b>：与 {@link JdbcCorpusManagementRepository} 完全一致——
 * 每个方法都在 {@link TransactionTemplate} 内执行
 * 「{@code set_config('app.tenant_id', ?, true)} → 业务 SQL」，确保租户上下文与
 * 业务 SQL 落在同一事务、同一条连接，RLS 策略对本次读写生效。
 *
 * <p><b>⚠️ 动态 vs 派生</b>：{@link DictionaryPort#TYPE_SOURCE_TYPE} 走
 * {@code rag_dictionary} 表，可维护；其余类型由 Java 枚举派生（只读），
 * 不触碰数据库，也不受租户隔离影响（枚举取值对所有租户一致）。
 *
 * <p><b>⚠️ 读写不对称是刻意的</b>（V7 双作用域）：
 * <ul>
 *   <li><b>读</b>：平台全局项（{@code tenant_id = rag_global_tenant_id()} 哨兵）∪ 本租户项，
 *       同 code 租户覆盖全局；</li>
 *   <li><b>写/删</b>：<b>恒只作用于本租户</b>——租户发布的每一项都是租户级私有项，
 *       永远产不出全局项。全局项只能由平台侧通道（超级用户/运维角色）写入。</li>
 * </ul>
 * 这不是能力缺失，而是越权闸门：若写路径放行全局哨兵租户，
 * 任一租户都能把字典项发布给全平台（详见 {@code rag_dictionary_write} 策略
 * 与 {@code scripts/rag-dict-scope-counterproof.sh} 的 P8 反证）。
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
        // ⚠️ 四个**动态类型**可维护；派生类型（TRUST_TIER 等）由 Java 枚举生成，
        //    改名会与检索期判定漂移，故只读。
        return TYPE_SOURCE_TYPE.equals(type)
                || TYPE_EDITION_PUBLISHER.equals(type)
                || TYPE_SUBJECT.equals(type)
                || TYPE_GRADE.equals(type)
                || TYPE_REVISION.equals(type);
    }

    @Override
    @NonNull
    public List<DictEntry> entriesOf(@NonNull RagManagementContext context, @NonNull String type) {
        if (DERIVED.containsKey(type)) {
            return DERIVED.get(type);
        }
        // 动态类型：平台全局项 ∪ 本租户项（RLS 的 _read 策略已把范围收在这两类里）。
        //
        // ⚠️ 这里的谓词<b>不能只靠 RLS</b>，也不能写成等值：
        //   · 写 `tenant_id = rag_current_tenant()` 会把全局行**静默滤掉**
        //     ——策略放开了、SQL 没跟上，表现是「字典没变化」而非报错。
        //   · 全局行的 tenant_id 是哨兵值（迁移 V8 把 V7 的 NULL 表示法统一成哨兵，
        //     语料链的复合外键与含 tenant_id 的主键决定了只能用哨兵），
        //     所以判别写成 `= rag_global_tenant_id()`，与策略、CHECK 三处同一个权威。
        List<DictEntry> rows = transactionTemplate.execute(status -> {
            // ⚠️ 平台全局档不建立会话租户：谓词里的 rag_current_tenant() 退化为 NULL，
            //    `IN (NULL, 哨兵)` 只剩全局项——正是「平台侧看到平台字典」的口径。
            //    这一句是**显式清空**而不是跳过（连接池/角色默认值会伪装成租户档）。
            RagSessionScope.apply(jdbcTemplate, context);
            return jdbcTemplate.query(
                    """
                    SELECT code, label, sort_order, enabled,
                           (tenant_id = rag_global_tenant_id()) AS is_global
                    FROM rag_dictionary
                    WHERE tenant_id IN (rag_current_tenant(), rag_global_tenant_id())
                      AND dict_type = ?
                    ORDER BY sort_order, code
                    """,
                    (rs, i) -> new DictEntry(
                            type,
                            rs.getString("code"),
                            rs.getString("label"),
                            rs.getInt("sort_order"),
                            rs.getBoolean("enabled"),
                            rs.getBoolean("is_global") ? Scope.PLATFORM : Scope.TENANT),
                    type);
        });
        return mergeOverride(rows == null ? List.of() : rows);
    }

    /**
     * 同 code 的「全局项 + 租户覆盖项」合并为一条，<b>租户项胜出</b>。
     *
     * <p>⚠️ **合并不依赖行的到达顺序**（此处曾被 IT 抓到真 bug）：
     * 【历史坑，V7 时期】早先用「全局在前、租户在后 + put 覆盖」实现，
     * 而 {@code ORDER BY (tenant_id IS NULL)} 在 PG 里把 FALSE 排在 TRUE 前——
     * 真实顺序是**租户在前**，于是全局项反过来覆盖了租户项，
     * 表现为「租户改了标签但界面纹丝不动」。
     * 靠 ORDER BY 的方向去承载一条业务规则，是把它藏在最容易被改错的地方；
     * 现在规则写在 merge 里，SQL 只管取数。
     *
     * <p>⚠️ 为什么在应用层合并而不是 SQL 层 {@code DISTINCT ON}：
     * 两者结果一致，但 {@code DISTINCT ON} 的取值顺序同样依赖 ORDER BY 前缀，
     * 同一个坑换个地方再踩一次；字典量级是每类型几十条，在 Java 里写明白更值。
     *
     * <p>包内可见仅为可测：合并规则（同 code 租户胜出 + 按 sort_order 重排）
     * 是纯函数，不该被「必须连库」的 IT 才覆盖到。
     */
    static List<DictEntry> mergeOverride(List<DictEntry> rows) {
        Map<String, DictEntry> byCode = new LinkedHashMap<>();
        for (DictEntry row : rows) {
            byCode.merge(row.code(), row,
                    (existing, incoming) -> existing.scope() == Scope.TENANT ? existing : incoming);
        }
        return byCode.values().stream()
                .sorted(Comparator.comparingInt(DictEntry::sortOrder).thenComparing(DictEntry::code))
                .toList();
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
                    tierLabels.getOrDefault(t.name(), t.name()), 0, true, Scope.PLATFORM));
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
                    methodLabels.getOrDefault(m.name(), m.name()), 0, true, Scope.PLATFORM));
        }
        map.put(TYPE_VERIFICATION_METHOD, methods);

        Map<String, String> licenseLabels = Map.of(
                "LICENSED", "已获授权",
                "FAIR_USE_QUOTATION", "合理引用",
                "PENDING", "未取得授权",
                "RESTRICTED", "禁止使用");
        List<DictEntry> licenses = new ArrayList<>();
        for (Map.Entry<String, String> e : licenseLabels.entrySet()) {
            licenses.add(new DictEntry(TYPE_LICENSE_STATUS, e.getKey(), e.getValue(),
                    0, true, Scope.PLATFORM));
        }
        map.put(TYPE_LICENSE_STATUS, licenses);

        return map;
    }
}
