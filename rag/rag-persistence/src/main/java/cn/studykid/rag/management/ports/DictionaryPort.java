package cn.studykid.rag.management.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.core.RagManagementContext;
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
 *
 * <p><b>全局 / 租户双作用域（V7 起）</b>：动态字典的读取集合 =
 * 平台全局项（{@link Scope#PLATFORM}，{@code tenant_id} 为 NULL）
 * ∪ 本租户私有项（{@link Scope#TENANT}）；<b>同 code 时租户项覆盖全局项</b>，
 * 与 {@code tenant_config_override}「只存覆盖项、缺省回落全局」同一套语义。
 * 写入与删除<b>恒为本租户</b>——全局项由平台侧通道发布，
 * 租户改不动也删不掉（{@code rag_dictionary} 的读写分策略；
 * 由 {@code scripts/rag-dict-scope-counterproof.sh} 的 P6/P7/P8 实测，
 * 不是推断）。
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

    /**
     * 出版社（版本体系）字典 —— 动态类型，可维护。
     *
     * <p>⚠️ 这是**版本体系**（人教版/苏教版…），不是出版机构（人民教育出版社）。
     * 检索期 `publisherFromDb` 按枚举 displayName 反查，
     * 故本字典的合法值应限定在 `EditionCoordinate.Publisher` 的取值范围内；
     * 超出范围的值落库后会让该校材的检索反查失败（表现为 500）。
     *
     * <p>做成可维护类型是为了让「新增一个版本体系」不必改前端代码。
     */
    String TYPE_EDITION_PUBLISHER = "EDITION_PUBLISHER";

    /**
     * 学科字典 —— 动态类型，可维护。
     *
     * <p>⚠️ 同出版社：检索期 `subjectFromDb` 按枚举 displayName 反查，
     * 非法值会让检索 500。
     */
    String TYPE_SUBJECT = "SUBJECT";

    /**
     * 年级字典 —— 动态类型，可维护。
     */
    String TYPE_GRADE = "GRADE";

    /**
     * 版次字典 —— 动态类型，可维护。
     *
     * <p>版次没有枚举约束（真实写法差异大：`2024修订` / `第3版` / `2024`），
     * 故这是四类中**唯一不参与枚举反查**的。
     */
    String TYPE_REVISION = "REVISION";

    /**
     * 字典项的作用域。
     *
     * <p>与 {@code rag_dictionary.scope} 列取值一一对应，
     * 沿用 platform 侧 {@code cap_config_release} 的同名约定（PLATFORM/TENANT），
     * 不另立第二套词汇。
     *
     * <p><b>⚠️ 为什么不用 {@code tenant_id = -1} 表示全局</b>：
     * 该列是 {@code UUID}，-1 无法表示；且「用一个真实存在的取值冒充无主」
     * 会被任何按租户等值过滤的 SQL 当成一个普通租户。
     * 全局的表示法是「租户维度不适用」= NULL + 判别列，见 V7 迁移头注释。
     */
    enum Scope {
        /** 平台全局：对所有租户只读可见，租户不可写、不可改、不可删。 */
        PLATFORM,
        /** 租户私有：严格隔离，且同 code 时覆盖全局项。 */
        TENANT
    }

    /**
     * 列出某类型的全部字典项（动态类型返回「全局 ∪ 本租户」，同 code 租户优先）。
     *
     * <p>平台全局档（{@code scope=PLATFORM}）不建立会话租户，
     * 于是 {@code rag_current_tenant()} 为 NULL、读策略只放行全局项——
     * 「∪ 本租户」在后半段自然退化为空集，无需第二条 SQL。
     *
     * @param context 作用域上下文（租户档／平台全局档）
     * @param type 字典类型
     * @return 字典项列表
     */
    @NonNull
    List<DictEntry> entriesOf(@NonNull RagManagementContext context, @NonNull String type);

    /** 该类型是否允许前端维护（动态字典可写；派生字典只读）。 */
    boolean isEditable(@NonNull String type);

    /** 写入 / 更新一项（仅对可编辑类型生效，冲突时按 (租户, 类型, code) upsert）。 */
    void upsertEntry(@NonNull RagTenantContext context, @NonNull DictEntry entry);

    /** 删除一项（仅对可编辑类型生效）。 */
    void deleteEntry(@NonNull RagTenantContext context, @NonNull String type, @NonNull String code);

    /**
     * 字典项。
     *
     * @param type 字典类型
     * @param code 稳定编码（写入业务表的实际取值）
     * @param label 展示名
     * @param sortOrder 排序（合并后按此升序呈现）
     * @param enabled 是否启用
     * @param scope 作用域；<b>写入路径恒为 {@link Scope#TENANT}</b>，
     *              {@code PLATFORM} 只可能出现在读取结果里
     */
    record DictEntry(
            @NonNull String type,
            @NonNull String code,
            @NonNull String label,
            int sortOrder,
            boolean enabled,
            @NonNull Scope scope) {

        /** 写入用的便捷构造：作用域只可能是租户级。 */
        public DictEntry(@NonNull String type, @NonNull String code, @NonNull String label,
                         int sortOrder, boolean enabled) {
            this(type, code, label, sortOrder, enabled, Scope.TENANT);
        }

        /** 是否为平台全局项（租户侧只读）。 */
        public boolean global() {
            return scope == Scope.PLATFORM;
        }
    }
}
