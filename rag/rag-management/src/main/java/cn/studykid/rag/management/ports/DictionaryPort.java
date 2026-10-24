package cn.studykid.rag.management.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.core.RagTenantContext;

/**
 * 字典端口（Point 2：枚举下拉的数据源）。
 *
 * <p><b>两类字典</b>：
 * <ul>
 *   <li><b>动态字典</b>（如 {@link #TYPE_SOURCE_TYPE}）：数据落在 {@code rag_dictionary} 表，
 *       由前端维护页增删改；{@link #isEditable(String)} 返回 {@code true}。</li>
 *   <li><b>派生字典</b>（{@link #TYPE_TRUST_TIER} / {@link #TYPE_VERIFICATION_METHOD} /
 *       {@link #TYPE_LICENSE_STATUS}）：由后端按 Java 枚举在查询时派生（只读），
 *       保证逻辑与持久化单一权威——这些枚举参与准入判定（权威等级门槛、
 *       版权状态可检索），绝不能让前端自由编辑而漂移。</li>
 * </ul>
 *
 * <p><b>⚠️ 派生字典的标签是后端权威源</b>：前端展示也可用自身标签表，
 * 但「有哪些合法值」以本端口返回为准，避免前后端各维护一份枚举值导致漂移。
 */
public interface DictionaryPort {

    /** 动态：来源类型（教材 / 教辅 / 论文 …）。 */
    String TYPE_SOURCE_TYPE = "SOURCE_TYPE";
    /** 派生：权威等级。 */
    String TYPE_TRUST_TIER = "TRUST_TIER";
    /** 派生：校验手段。 */
    String TYPE_VERIFICATION_METHOD = "VERIFICATION_METHOD";
    /** 派生：版权状态。 */
    String TYPE_LICENSE_STATUS = "LICENSE_STATUS";

    /** 列出某类型的全部字典项（动态类型按 sort_order 升序）。 */
    @NonNull
    List<DictEntry> entriesOf(@NonNull RagTenantContext context, @NonNull String type);

    /** 该类型是否允许前端维护（动态字典可写；派生字典只读）。 */
    boolean isEditable(@NonNull String type);

    /** 写入 / 更新一项（仅对可编辑类型生效，冲突时按 (租户, 类型, code) upsert）。 */
    void upsertEntry(@NonNull RagTenantContext context, @NonNull DictEntry entry);

    /** 删除一项（仅对可编辑类型生效）。 */
    void deleteEntry(@NonNull RagTenantContext context, @NonNull String type, @NonNull String code);

    /** 字典项。 */
    record DictEntry(
            @NonNull String type,
            @NonNull String code,
            @NonNull String label,
            int sortOrder,
            boolean enabled) {
    }
}
