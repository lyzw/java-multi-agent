package cn.studykid.multiagent.core.orchestration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;

/**
 * 契约 Schema 的<b>共享关键字口径</b>（多级 Agent · 10 §3.4）。
 *
 * <p>⚠️ <b>本类存在的唯一理由是把「Schema 方言」收成一份</b>：
 * 子输出契约（{@code ChildResultContractValidator}）与父侧路由契约的
 * {@code inputContract} 必须是<b>同一套关键字口径</b> ——
 * 两套白名单迟早分叉，而分叉的现场是
 * 「模型按父的 Schema 填了字段，运行期校验器不认识它」（或反过来：
 * 契约里写了校验器会执行的约束，父侧却宣称它不算数）。
 * 那正是本项目反复出现的「看起来生效、实际不生效」。
 *
 * <p>⚠️ <b>白名单只作用于 Schema 顶层</b>（与 {@code ChildResultContractValidator}
 * 同口径，不是遗漏）：嵌套子 Schema 的关键字由各自的类型检查负责，
 * 顶层则一条不放过 —— 不认识的关键字可能正承载约束
 * （{@code maxLength} / {@code pattern} / {@code minimum} …），
 * 半懂半不懂地放行等于「假装校验过」。
 */
public final class ContractSchemaKeywords {

    /**
     * 支持的关键字白名单（其余关键字视为不支持 → 拒绝契约）。
     *
     * <p>⚠️ 新增关键字必须<b>同时</b>补进校验器的执行逻辑：
     * 只进白名单不实现，等于宣称约束生效而实际忽略它。
     */
    public static final @NonNull Set<String> SUPPORTED = Set.of(
            "type", "required", "properties", "additionalProperties", "items", "enum",
            "description", "title", "$schema", "$id");

    /** {@code capabilities} 项的词表：小写字母开头，可含数字与 {@code . _ -}。 */
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9._-]{1,63}$");

    private ContractSchemaKeywords() {
    }

    /**
     * 校验一个 Schema 的<b>顶层</b>关键字是否都在白名单内。
     *
     * @param keywordNames Schema 顶层的键集合
     * @return 不认识的关键字（按输入顺序）；全部认识时为空
     */
    public static @NonNull List<String> unsupported(@NonNull Collection<String> keywordNames) {
        List<String> unsupported = new ArrayList<>();
        for (String name : keywordNames) {
            if (!SUPPORTED.contains(name)) {
                unsupported.add(name);
            }
        }
        return unsupported;
    }

    /**
     * 单个能力标签是否合法。
     *
     * @param capability 能力标签
     * @return 合法返回 {@code true}
     */
    public static boolean isValidCapability(@NonNull String capability) {
        return CAPABILITY.matcher(capability).matches();
    }
}
