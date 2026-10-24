package cn.studykid.multiagent.application.cap;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.ConfigObjectVersion;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

/**
 * 「Agent 当前生效版本」的<b>唯一</b>解析口径（P0-5 修复）。
 *
 * <p>⚠️ <b>为什么必须有唯一口径</b>：
 * 运行期有两处需要回答「Agent 当前该用哪个版本」——
 * {@link PermissionResolver#resolveCurrent}（解析当前闭包）与
 * {@link CachingClosureResolver}（缓存新鲜度判定）。
 * 两处若各写一遍，迟早漂移；而漂移出来的差异只会让<b>缓存与解析对不上</b>：
 * 缓存认为「没变」而解析认为「变了」→ 陈旧闭包被继续使用。
 *
 * <p><b>口径（§6.3 第 2 条）</b>：
 * <pre>
 * TENANT 指针 → 指针指向的 PUBLISHED release → object_versions → agent id → 精确版本
 * </pre>
 *
 * <p>⚠️ <b>为什么不能用「该 Agent 最新已发布版本行」</b>：
 * 版本行是<b>不可变</b>的（§6.1），发布与回滚都<b>不删除</b>历史行，
 * 只移动指针。于是「最新已发布版本行」在回滚后仍然是<b>被回滚掉的那个版本</b> ——
 * 租户回滚对运行期完全无效（回滚只是「治理动作」，配置一点没变）。
 * 这正是 P0-5 缺陷的根因。
 *
 * <p>⚠️ <b>唯一的回退路径</b>：当<b>当前发布头没有引用该 Agent</b> 时
 * （租户还没把它纳入 CAP 发布集合，例如它由遗留的 {@code /api/agents} 通道发布），
 * 退回「该 Agent 最新已发布版本行」。
 * 该回退<b>只在发布头未引用该对象时生效</b>，因此
 * <b>不会削弱回滚语义</b>：只要该 Agent 在发布头里，指针口径优先生效。
 */
public final class CurrentAgentVersion {

    private static final Logger log = LoggerFactory.getLogger(CurrentAgentVersion.class);

    private CurrentAgentVersion() {
    }

    /**
     * 解析 Agent 当前生效的版本号。
     *
     * @param publishedVersions 发布版本解析端口
     * @param agentDefs         Agent 版本行仓储
     * @param tenantId          租户标识
     * @param agentId           Agent 标识
     * @return 当前生效版本号；解析不出时为 {@link Optional#empty()}
     */
    @NonNull
    public static Optional<Integer> resolve(@NonNull PublishedVersionPort publishedVersions,
                                             @NonNull CapAgentDefPort agentDefs,
                                             @NonNull String tenantId,
                                             @NonNull String agentId) {
        Optional<Integer> pinned = publishedVersions.currentTenantVersion(
                tenantId, ConfigObjectVersion.TYPE_AGENT, agentId);
        if (pinned.isPresent()) {
            return pinned;
        }
        // ⚠️ 回退：当前发布头未引用该 Agent → 该 Agent 不在「当前发布集合」内。
        //    此时退回「最新已发布版本行」，与本缺陷修复前的行为一致，
        //    避免把「从未走过 CAP 发布通道的租户」整体打成不可用。
        Optional<CapAgentDef> latest = agentDefs.findLatestPublished(tenantId, agentId);
        if (latest.isPresent()) {
            log.debug("当前发布头未引用该 Agent，回退到最新已发布版本行: "
                            + "tenant={}, agent={}, version={}",
                    tenantId, agentId, latest.get().version());
        }
        return latest.map(CapAgentDef::version);
    }
}
