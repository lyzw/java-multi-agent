package cn.studykid.multiagent.application.cap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.cap.CapObjectType;
import cn.studykid.multiagent.core.cap.EffectiveClosure;
import cn.studykid.multiagent.ports.mcp.McpManifestPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.PublishedVersionPort;

/**
 * 闭包解析缓存装饰器（P2-T9 · §7.3 缓存 · 07 §4 配置读取与缓存失效）。
 *
 * <p>⚠️ <b>本装饰器的第一原则：缓存不得绕过权威校验</b>（§7.3）。
 * 命中缓存<b>不是</b>「闭包没变」的证明，它只是<b>免去了一次重解析</b>；
 * 免得凭什么，必须由权威源回答。
 *
 * <p><b>命中条件（可证）</b>：缓存条目的依赖版本集合与「此刻重新解析会读到的版本」
 * 完全一致。由于 {@code cap_*_def} 的版本行<b>不可变</b>（§6.1），
 * 「依赖版本集合一致」⟹「重新解析会得到逐字段相同的闭包」⟹「指纹必然相同」。
 * 校验步骤与 {@link PermissionResolver} 的解析步骤<b>逐一对应</b>，
 * 所以这不是启发式判断，而是解析过程的镜像。
 *
 * <p>⚠️ <b>「仅全局序号变、闭包未变」绝不误判授权失败</b>（§7.3 原文）：
 * 依赖集合里<b>没有</b>全局发布序号（{@code configVersion}）。
 * 无关对象发布新版本 → 依赖版本集合不变 → 命中 → 闭包未变 → 指纹未变 →
 * 不产生 {@code CONFIG_FINGERPRINT_STALE}。
 * 若把全局序号放进依赖集合，每次任意对象发布都会让本 Run 无故失效。
 *
 * <p>⚠️ <b>命中不是授权结论</b>：缓存只回答「闭包内容是否变化」。
 * 授权判定仍由 {@link InvocationValidator} 逐因子完成，
 * 且指纹仍要与 Run 快照比对（§5.3 步骤 7）——本装饰器<b>不取代</b>该比对。
 *
 * <p>⚠️ <b>MCP 固定清单的漂移由本装饰器一并检测</b>（P4 落地）：
 * MCP 固定清单是<b>实时源</b>（{@code McpManifestPort}），不经发布指针，
 * 因此「清单变了但版本没变」不会被依赖版本集合捕捉 ——
 * 必须单独比对清单内容（07 §4「含 MCP 固定清单变更」）。
 * 清单不可用时<b>同样作废</b>缓存条目，
 * 使「读不到清单」与「清单变了」收敛到同一个原因码
 * （{@code CONFIG_FINGERPRINT_STALE}），避免同一类故障因缓存状态不同给出两个码。
 *
 * <p>⚠️ <b>有界 LRU</b>：条目数上限固定，超出后淘汰最久未用的条目。
 * 无界缓存会让「每个 Agent 一个条目」在租户规模增长后成为内存泄漏。
 */
public class CachingClosureResolver implements ClosureResolver {

    private static final Logger log = LoggerFactory.getLogger(CachingClosureResolver.class);

    /** 默认最大条目数。 */
    public static final int DEFAULT_MAX_ENTRIES = 512;

    private final ClosureResolver delegate;
    private final PublishedVersionPort publishedVersions;
    private final CapToolDefPort toolDefs;
    private final CapAgentDefPort agentDefs;
    private final McpManifestPort manifest;
    private final int maxEntries;

    /** 命中计数（可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong hits =
            new java.util.concurrent.atomic.AtomicLong();
    /** 未命中计数（可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong misses =
            new java.util.concurrent.atomic.AtomicLong();

    /** 有界 LRU：{@code removeEldestEntry} 在超出上限时淘汰最久未用的条目。 */
    private final Map<String, Entry> cache;

    public CachingClosureResolver(@NonNull ClosureResolver delegate,
                                  @NonNull PublishedVersionPort publishedVersions,
                                  @NonNull CapToolDefPort toolDefs,
                                  @NonNull CapAgentDefPort agentDefs,
                                  @NonNull McpManifestPort manifest) {
        this(delegate, publishedVersions, toolDefs, agentDefs, manifest, DEFAULT_MAX_ENTRIES);
    }

    public CachingClosureResolver(@NonNull ClosureResolver delegate,
                                  @NonNull PublishedVersionPort publishedVersions,
                                  @NonNull CapToolDefPort toolDefs,
                                  @NonNull CapAgentDefPort agentDefs,
                                  @NonNull McpManifestPort manifest,
                                  int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries 必须为正数，收到: " + maxEntries);
        }
        this.delegate = delegate;
        this.publishedVersions = publishedVersions;
        this.toolDefs = toolDefs;
        this.agentDefs = agentDefs;
        this.manifest = manifest;
        this.maxEntries = maxEntries;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            /**
             * ⚠️ **必须写全限定名 {@code CachingClosureResolver.Entry}**：
             * 在匿名 {@code LinkedHashMap} 子类体内，简单名 {@code Entry} 会被
             * **继承来的成员类型** {@code Map.Entry} 遮蔽（继承成员类型优先于外层类作用域），
             * 于是签名会变成 {@code Map.Entry<String, Map.Entry>} —— 既不覆盖
             * {@code LinkedHashMap.removeEldestEntry}，又与之同名冲突，
             * 整个 {@code agent-application} 模块编译失败。
             */
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<String, CachingClosureResolver.Entry> eldest) {
                return size() > CachingClosureResolver.this.maxEntries;
            }
        });
    }

    @Override
    public @NonNull EffectiveClosure resolve(@NonNull String tenantId,
                                              @NonNull String agentId,
                                              int agentVersion) {
        String key = key(tenantId, agentId, "v" + agentVersion);
        Entry hit = cache.get(key);
        if (hit != null && isFresh(tenantId, hit, agentVersion, true)) {
            hits.incrementAndGet();
            return hit.closure();
        }
        misses.incrementAndGet();
        EffectiveClosure closure = delegate.resolve(tenantId, agentId, agentVersion);
        cache.put(key, newEntry(tenantId, closure, agentVersion, true));
        return closure;
    }

    @Override
    public @NonNull EffectiveClosure resolveCurrent(@NonNull String tenantId,
                                                     @NonNull String agentId) {
        String key = key(tenantId, agentId, "current");
        Entry hit = cache.get(key);
        if (hit != null && isFresh(tenantId, hit, hit.agentVersion(), false)) {
            hits.incrementAndGet();
            return hit.closure();
        }
        misses.incrementAndGet();
        EffectiveClosure closure = delegate.resolveCurrent(tenantId, agentId);
        cache.put(key, newEntry(tenantId, closure, closure.agentVersion(), false));
        log.debug("闭包缓存未命中，回源重解析: tenant={}, agent={}", tenantId, agentId);
        return closure;
    }

    /**
     * 丢弃某个租户 + Agent 的全部缓存条目。
     *
     * <p>供「配置发布后立即失效」等显式失效场景调用（P5 发布流程）。
     *
     * @return 被丢弃的条目数
     */
    public int invalidate(@NonNull String tenantId, @NonNull String agentId) {
        String prefix = tenantId + "|" + agentId + "|";
        synchronized (cache) {
            int before = cache.size();
            cache.keySet().removeIf(k -> k.startsWith(prefix));
            return before - cache.size();
        }
    }

    /** @return 当前缓存条目数（可观测）。 */
    public int size() {
        return cache.size();
    }

    /** @return 缓存命中累计次数（可观测）。 */
    public long hitCount() {
        return hits.get();
    }

    /** @return 缓存未命中累计次数（可观测）。 */
    public long missCount() {
        return misses.get();
    }

    // ── 命中判定 ────────────────────────────────────────────────────

    /**
     * 权威新鲜度校验：逐个依赖对象重新读取权威版本，与缓存条目记录比对。
     *
     * @param agentVersion    期望的 Agent 版本
     * @param agentIsFixed    {@code true} = Agent 版本由调用方固定（{@code resolve}）；
     *                        {@code false} = 取当前最新已发布版本（{@code resolveCurrent}）
     * @return 依赖版本集合是否完全一致
     */
    private boolean isFresh(@NonNull String tenantId, @NonNull Entry entry,
                            int agentVersion, boolean agentIsFixed) {
        // ── Agent 版本 ────────────────────────────────────────────
        if (agentIsFixed) {
            if (entry.agentVersion() != agentVersion) {
                return false;
            }
        } else {
            // ⚠️⚠️ 必须与 {@link PermissionResolver#resolveCurrent} **同口径**
            //    （P0-5 修复）：解析侧走「TENANT 指针 → release → object_versions」，
            //    这里若仍用「最新已发布版本行」，回滚后本处会判定「版本没变」
            //    （findLatestPublished 仍返回被回滚掉的版本）→ 陈旧闭包继续被使用。
            Optional<Integer> current = CurrentAgentVersion.resolve(
                    publishedVersions, agentDefs, tenantId, entry.agentId());
            if (current.isEmpty() || current.get() != entry.agentVersion()) {
                return false;
            }
        }

        // ── Agent 内容指纹（同一版本行被就地改写 → 闭包必须重解析）──
        // ⚠️ 只比版本号不够：版本行按约定不可变，但**约定不是机制**。
        //    内容指纹覆盖编排声明（路由策略引用、输出契约引用、超时/深度/次数），
        //    它变了就意味着本 Agent 的行为性内容变了（MA-T15）。
        //    ⚠️ 读不到定义行 → 无法确认新鲜度 → 判定为不新鲜（fail-closed）。
        String currentAgentFingerprint = agentDefs
                .find(tenantId, entry.agentId(), entry.agentVersion())
                .map(CapAgentDef::configFingerprint)
                .orElse("");
        if (currentAgentFingerprint.isEmpty()
                || !currentAgentFingerprint.equals(entry.agentConfigFingerprint())) {
            return false;
        }

        // ── 依赖对象版本 ──────────────────────────────────────────
        for (Dependency dependency : entry.dependencies()) {
            Optional<Integer> current = currentVersion(dependency);
            // ⚠️ 读不到权威版本 = <b>无法确认新鲜度</b> → 判定为不新鲜（回源重解析）。
            //    绝不「读不到就认为没变」—— 那是缓存层的 fail-open。
            if (current.isEmpty() || current.get() != dependency.version()) {
                return false;
            }
        }

        // ── MCP 固定清单（07 §4「含 MCP 固定清单变更」）─────────────
        if (!isManifestFresh(entry)) {
            return false;
        }
        return true;
    }

    /**
     * MCP 固定清单的新鲜度判定。
     *
     * <p>⚠️ <b>清单变了 → 缓存条目作废</b>（07 §4 原文：
     * 「仅当 Run 依赖闭包内的对象（Agent/Skill/Tool/MCP）发生版本或指纹变化
     * <b>（含 MCP 固定清单变更）</b>时，{@code closureFingerprint} 才变化并失效」）。
     * 清单不在版本体系里（它是实时源），所以必须<b>单独</b>比对。
     *
     * <p>⚠️ <b>清单不可用 → 也作废</b>（这是「同一类变化不得两种码」的关键）：
     * 若不可用时保留缓存条目，则
     * <ul>
     *   <li>有缓存：闭包里的 manifestOps 仍是旧值 → 指纹与快照一致 → 步骤 1 通过
     *       → 步骤 3 读不到清单 → {@code MCP_TOOL_DRIFTED}；</li>
     *   <li>无缓存：闭包按空清单重建 → 指纹与快照不一致 → 步骤 1 失败
     *       → {@code CONFIG_FINGERPRINT_STALE}。</li>
     * </ul>
     * 同一种故障（清单读不到）因缓存状态不同给出<b>两个原因码</b> ——
     * 这正是 §5.8 禁止的「同一类变化两种码」。
     * 故此处一律作废，让两条路径都落到 {@code CONFIG_FINGERPRINT_STALE}
     * （闭包无法按当前清单复现 = 指纹失效，语义也准确）。
     *
     * <p>代价：一个 MCP server 宕机会让相关 Agent 的闭包反复重解析。
     * 这是<b>可接受的正确性代价</b> —— 反过来（保留缓存）才是正确性缺陷。
     */
    private boolean isManifestFresh(@NonNull Entry entry) {
        for (EffectiveClosure.McpBinding binding : entry.closure().mcpServers()) {
            McpManifestPort.ManifestSnapshot live = manifest.manifest(binding.serverId());
            if (!live.available()) {
                // 读不到 → 无法确认 → 一律作废（见上）
                return false;
            }
            if (!live.ops().equals(binding.manifestOps())) {
                return false;
            }
        }
        return true;
    }

    private @NonNull Optional<Integer> currentVersion(@NonNull Dependency dependency) {
        if (dependency.kind() == DependencyKind.MCP_TOOL) {
            // ⚠️ MCP 工具的版本由 (serverId, op) 定位，<b>不经发布指针</b>
            //    （见 PermissionResolver 的 findByMcpBinding 调用）。
            //    必须用同一口径复查，否则「指针没变但工具行变了」会被漏掉。
            return toolDefs.findByMcpBinding(dependency.objectId(), dependency.mcpOp())
                    .map(cn.studykid.multiagent.core.cap.CapToolDef::version);
        }
        return publishedVersions.currentVersion(dependency.capType(), dependency.objectId());
    }

    // ── 条目构造 ────────────────────────────────────────────────────

    private @NonNull Entry newEntry(@NonNull String tenantId,
                                    @NonNull EffectiveClosure closure, int agentVersion,
                                    boolean agentIsFixed) {
        List<Dependency> dependencies = new ArrayList<>();
        for (EffectiveClosure.SkillBinding skill : closure.skills()) {
            dependencies.add(Dependency.pointer(CapObjectType.SKILL, skill.skillId(), skill.version()));
        }
        for (EffectiveClosure.ToolBinding tool : closure.tools()) {
            if (tool.isMcp() && tool.mcpServerId() != null && tool.mcpToolName() != null) {
                dependencies.add(Dependency.mcpTool(tool.mcpServerId(), tool.mcpToolName(),
                        tool.version()));
            } else {
                dependencies.add(Dependency.pointer(CapObjectType.TOOL, tool.toolId(), tool.version()));
            }
        }
        for (EffectiveClosure.McpBinding binding : closure.mcpServers()) {
            dependencies.add(Dependency.pointer(CapObjectType.MCP_SERVER,
                    binding.serverId(), binding.version()));
        }
        // ⚠️ Agent 自身的**内容指纹**必须记进条目：
        //    只比版本号的话，「同一版本行被就地改写」（例如编排声明被改）
        //    不会被察觉 —— 缓存会继续供出陈旧闭包，
        //    于是「指纹说没变，行为却变了」在缓存层就被抹平了（MA-T15）。
        String agentConfigFingerprint = agentDefs
                .find(tenantId, closure.agentId(), agentVersion)
                .map(CapAgentDef::configFingerprint)
                .orElse("");
        return new Entry(closure, closure.agentId(), agentVersion,
                List.copyOf(dependencies), agentConfigFingerprint);
    }

    private static @NonNull String key(@NonNull String tenantId, @NonNull String agentId,
                                       @NonNull String versionMarker) {
        return tenantId + "|" + agentId + "|" + versionMarker;
    }

    // ── 条目与依赖 ──────────────────────────────────────────────────

    /** 依赖对象的版本解析口径。 */
    enum DependencyKind {
        /** 经发布指针解析（{@code PublishedVersionPort}）。 */
        POINTER,
        /** 经 (MCP server, op) 定位（{@code CapToolDefPort.findByMcpBinding}）。 */
        MCP_TOOL
    }

    /**
     * 一个依赖对象及其权威版本。
     *
     * @param kind     版本解析口径
     * @param capType  对象类型（{@link DependencyKind#POINTER} 时有效）
     * @param objectId 对象标识（{@link DependencyKind#POINTER} 时为逻辑 id）
     * @param mcpOp    MCP 远端操作名（{@link DependencyKind#MCP_TOOL} 时有效）
     * @param version  权威版本号
     */
    record Dependency(@NonNull DependencyKind kind,
                      @NonNull CapObjectType capType,
                      @NonNull String objectId,
                      @NonNull String mcpOp,
                      int version) {

        static @NonNull Dependency pointer(@NonNull CapObjectType type, @NonNull String id,
                                           int version) {
            return new Dependency(DependencyKind.POINTER, type, id, "", version);
        }

        static @NonNull Dependency mcpTool(@NonNull String serverId, @NonNull String op,
                                           int version) {
            return new Dependency(DependencyKind.MCP_TOOL, CapObjectType.TOOL, serverId, op, version);
        }
    }

    /**
     * 缓存条目。
     *
     * @param closure      解析出的闭包
     * @param agentId      Agent 标识
     * @param agentVersion Agent 版本
     * @param dependencies  依赖对象及其版本（新鲜度校验的判据）
     */
    /**
     * 一条缓存条目。
     *
     * @param closure                缓存的闭包
     * @param agentId                Agent 标识
     * @param agentVersion           解析时的 Agent 版本
     * @param dependencies           依赖对象及其权威版本
     * @param agentConfigFingerprint 解析时 Agent 版本行的<b>内容指纹</b> ——
     *                             与版本号一起构成新鲜度判据（版本行被就地改写时内容指纹变）
     */
    record Entry(@NonNull EffectiveClosure closure,
                 @NonNull String agentId,
                 int agentVersion,
                 @NonNull List<Dependency> dependencies,
                 @NonNull String agentConfigFingerprint) {
    }
}
