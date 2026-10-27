package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 平台字典端口。
 *
 * <p><b>为什么要有字典表</b>：前端下拉枚举原本散落在各表单的 TS 常量里，
 * 改一个中文标签要改代码、发版、重新构建。字典表把这些取值收敛为<b>数据</b>，
 * 平台侧可自行新增 / 改名 / 停用，不必改前端代码。
 *
 * <p><b>⚠️ built_in 的语义（关键纪律）</b>：
 * <ul>
 *   <li>{@code built_in = true}：与<b>代码枚举强绑定</b>（如 {@code http.method}、
 *       {@code tool.side_effect}）。后端写业务表时按 Java 枚举校验取值，因此
 *       <b>禁止删除、禁止改 item_code</b>——否则历史行的取值变成悬空引用。
 *       中文标签、排序、停用可以改。</li>
 *   <li>{@code built_in = false}：纯业务数据字典（如 {@code rag.subject}），
 *       可自由增删改停。</li>
 * </ul>
 *
 * <p><b>⚠️ 一律不物理删除</b>：字典项被业务行引用后，物理删除会让历史数据出现
 * 「取值不在字典里」的悬空状态，排查成本远高于一条停用标记。
 */
public interface DictionaryPort {

    /** 列出全部字典（含停用，由调用方决定是否过滤展示）。 */
    @NonNull List<DictionaryRow> listDictionaries();

    @NonNull Optional<DictionaryRow> findDictionary(@NonNull String dictCode);

    /** 列出某字典的全部取值（含停用）。 */
    @NonNull List<DictionaryItemRow> listItems(@NonNull String dictCode);

    @NonNull Optional<DictionaryItemRow> findItem(@NonNull String dictCode, @NonNull String itemCode);

    /** 新建字典（built_in 恒为 false：内置字典由迁移脚本播种，不接受应用层创建）。 */
    void insertDictionary(@NonNull DictionaryRow row);

    /**
     * 更新字典的名称 / 说明 / 排序 / 启用。
     *
     * <p>⚠️ 不含 {@code dict_code}：编码是前后端契约，创建后不可改。
     */
    void updateDictionary(@NonNull DictionaryRow row);

    /** 写入或更新一项（按 dict_code + item_code 唯一键 upsert）。 */
    void upsertItem(@NonNull DictionaryItemRow row);

    /**
     * 启用 / 停用一项（<b>不是</b>物理删除）。
     *
     * @param enabled false 表示停用
     */
    void setItemEnabled(@NonNull String dictCode, @NonNull String itemCode, boolean enabled);

    /** 字典类型行。 */
    record DictionaryRow(
            @NonNull String dictCode,
            @NonNull String dictName,
            String description,
            boolean builtIn,
            boolean enabled,
            int sortOrder) {
    }

    /** 字典项行。 */
    record DictionaryItemRow(
            @NonNull String dictCode,
            @NonNull String itemCode,
            @NonNull String itemLabel,
            int sortOrder,
            boolean builtIn,
            boolean enabled) {
    }
}
