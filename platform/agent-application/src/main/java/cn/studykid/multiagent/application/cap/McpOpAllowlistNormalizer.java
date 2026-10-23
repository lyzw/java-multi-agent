package cn.studykid.multiagent.application.cap;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * MCP 可调用操作的规范化与交集（W-PERM-P4-T3 · §4.4）。
 *
 * <p>§4.4 的<b>全部</b>语义都在本类，无第二处实现：
 * <pre>
 *   Server.exposedToolAllowlist = null → ServerAllowedOps := ManifestOps
 *   Server.exposedToolAllowlist = []   → ServerAllowedOps := ∅
 *   Server.exposedToolAllowlist = L   → ServerAllowedOps := L ∩ ManifestOps
 *
 *   Skill.toolAllowlist = null         → SkillAllowedOps := ServerAllowedOps
 *   Skill.toolAllowlist = []           → SkillAllowedOps := ∅
 *   Skill.toolAllowlist = L            → SkillAllowedOps := L ∩ ServerAllowedOps
 * </pre>
 *
 * <p>⚠️ <b>为什么 {@code null} 与 {@code []} 必须分开</b>：
 * {@code null} = 「未配置该层附加限制」→ <b>继续</b>下层（可调用范围由更严的一层决定）；
 * {@code []} = 「显式声明该层一个都不许」→ <b>交集为空</b>。
 * 两者若用同一个默认值表达，「服务端只暴露这三个」会被误读成「服务端没限制」，
 * 于是 {@code AllowedOps} 从 ∅ 变成全集 —— <b>这是把「显式收紧」误读成「未配置」</b>，
 * 方向恰好是 fail-open。
 *
 * <p>⚠️ <b>为什么禁止对未规范化的 {@code null} 直接做集合运算</b>（§4.4 原文）：
 * {@code null ∩ X} 在多数语言里是 {@code null} 或抛 NPE，
 * 而 {@code null} 在这里的语义是「不设限」—— 直接参与运算会把「不设限」变成「无结果」或「全放行」，
 * 两者都不是设计意图。本类先把三态映射成<b>确定的集合</b>，再做交集。
 *
 * <p>⚠️ <b>返回的集合一律不可变且已排序</b>：
 * 规范化结果会进入闭包指纹（§6.4「MCP 固定清单」），指纹要求逐字节可复现；
 * 若集合的迭代序随实现而变，同一份配置会算出两个指纹。
 */
public final class McpOpAllowlistNormalizer {

    private McpOpAllowlistNormalizer() {
    }

    /**
     * {@code ServerAllowedOps}（§4.4 规范化表 · 服务端层）。
     *
     * @param exposedToolAllowlist 服务端暴露清单；{@code null} = 未配置，{@code []} = 显式空
     * @param manifestOps          当前固定清单（{@code ManifestOps}）
     * @return 规范化后的服务端允许集（不可变、有序）
     */
    public static @NonNull Set<String> serverAllowedOps(
            @Nullable List<String> exposedToolAllowlist,
            @NonNull Set<String> manifestOps) {
        if (exposedToolAllowlist == null) {
            // null = 未配置 → 取清单全集（清单本身是权威上限）
            return sorted(manifestOps);
        }
        if (exposedToolAllowlist.isEmpty()) {
            // [] = 显式空清单 → 任何操作都不允许
            return Set.of();
        }
        return intersect(exposedToolAllowlist, manifestOps);
    }

    /**
     * {@code SkillAllowedOps}（§4.4 规范化表 · Skill 层）。
     *
     * @param toolAllowlist   Skill 在该 server 上的允许清单；{@code null} = 未配置，{@code []} = 显式空
     * @param serverAllowedOps 服务端层规范化结果
     * @return 规范化后的 Skill 允许集（不可变、有序）
     */
    public static @NonNull Set<String> skillAllowedOps(
            @Nullable List<String> toolAllowlist,
            @NonNull Set<String> serverAllowedOps) {
        if (toolAllowlist == null) {
            // null = 未配置 → 沿用服务端层结果
            return sorted(serverAllowedOps);
        }
        if (toolAllowlist.isEmpty()) {
            // [] = 显式空清单 → 任何操作都不允许
            return Set.of();
        }
        return intersect(toolAllowlist, serverAllowedOps);
    }

    /**
     * 两个允许集的交集（§4.4 {@code SkillAllowedOps ∩ ClosureOps} 的运算部分）。
     *
     * @param left  左集合
     * @param right 右集合
     * @return 交集（不可变、有序）
     */
    public static @NonNull Set<String> intersect(@NonNull Set<String> left,
                                                 @NonNull Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return Collections.unmodifiableSet(result);
    }

    /**
     * 列表与集合的交集（服务端层用；{@code L ∩ ManifestOps}）。
     *
     * @param list  允许清单
     * @param set   基准集合
     * @return 交集（不可变、有序）
     */
    public static @NonNull Set<String> intersect(@NonNull List<String> list,
                                                 @NonNull Set<String> set) {
        return intersect(new LinkedHashSet<>(list), set);
    }

    /**
     * 生成有序不可变副本。
     *
     * @param ops 原始集合
     * @return 有序不可变副本
     */
    public static @NonNull Set<String> sorted(@NonNull Set<String> ops) {
        return Collections.unmodifiableSet(new TreeSet<>(ops));
    }
}
