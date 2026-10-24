package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapObjectType;

/**
 * 发布版本解析端口（§6.2「当前 PUBLISHED release → object_versions → 解析 id→精确版本」）。
 *
 * <p>这是 §6.2 三层引用模型的<b>中间层</b>：对象定义只存逻辑 id，
 * 「当前该用哪个版本」由<b>当前已发布 release</b> 回答。
 *
 * <p>⚠️ <b>实现方（P5）必须遵守 §6.3：当前版本不得以 {@code max(seq)} 选择</b>。
 * 原因：{@code config_publish_seq} 的分配序 ≠ 提交序（§9.2 的 PUB-2 不变量），
 * 且回滚发布（§6.3.6）会保留原 release —— 按 seq 取最大可能选中已回滚的发布。
 * 正确口径是「<b>指针指向的</b> PUBLISHED release」。
 *
 * <p>⚠️ <b>解析失败必须表现为「空」而非「兜底版本」</b>：
 * 解析不到即意味着该对象当前不可用，调用方应 fail-closed 拒绝；
 * 若实现方返回一个「默认版本」（如 version=1），未发布的新对象会被当成已发布对象使用 ——
 * 这是一条直接的越权路径。
 */
public interface PublishedVersionPort {

    /**
     * 解析对象当前已发布的确切版本。
     *
     * @param type    对象类型（<b>必须带类型</b>：Tool / Skill / MCP Server 的 id 命名空间相互独立）
     * @param objectId 对象逻辑 id
     * @return 当前已发布版本号；该对象当前无已发布版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<Integer> currentVersion(@NonNull CapObjectType type, @NonNull String objectId);

    /**
     * 解析<b>租户级</b>对象当前已发布的确切版本（§6.3 第 2 条）。
     *
     * <p>口径与 {@link #currentVersion} 相同，但走 <b>TENANT 指针</b>：
     * <pre>TENANT 指针 → 指针指向的 PUBLISHED release → object_versions → id→精确版本</pre>
     *
     * <p>⚠️ <b>为什么必须与平台作用域分开</b>：
     * PLATFORM 作用域的发布头里<b>没有</b>租户对象（Agent）的版本引用
     * （{@code ConfigValidationGate} 明令禁止 PLATFORM 发布携带 Agent 定义），
     * 拿平台解析结果当租户对象的当前版本，会解析到<b>另一个命名空间</b>去 ——
     * 那是静默的越权路径。
     *
     * <p>⚠️ <b>默认实现返回空（fail-closed）</b>：
     * 本端口保持「只有一个抽象方法」，既有 lambda 实现无需改动；
     * 但<b>没有实现本方法的替身一律解析不出租户级版本</b>，
     * 调用方必须自行决定「解析不出」该如何处置（不得静默改用别的口径）。
     */
    @NonNull
    default Optional<Integer> currentTenantVersion(@NonNull String tenantId,
                                                   @NonNull String type,
                                                   @NonNull String objectId) {
        return Optional.empty();
    }
}
