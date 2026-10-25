package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.orchestration.RoutingStrategy;

/**
 * 路由策略的定义行（多级 Agent v1.1 · 第十四批）。
 *
 * <p>⚠️ <b>路由策略是配置对象，不是运行期数据</b>：它描述「路由 Agent 可以把工作
 * 交给哪些子 Agent、以及怎么选」，发布期固化、运行期只读。
 * 与输出契约同构 —— 配置对象与运行期数据必须分表，
 * 否则「运行数据覆盖配置定义」会让路由行为不可复核。
 *
 * <p>⚠️ <b>必须固定版本</b>：Agent 的 {@code routingPolicyRef} 形如 {@code id@version}，
 * 禁止 {@code latest} 漂移别名 —— 与子 Agent 引用、输出契约引用同口径。
 * 策略改了就是新版本，旧版本仍可被历史 Run 按快照引用。
 *
 * <p>⚠️ <b>本模型是「本批取舍」，不是评审冻结的模型</b>：
 * 内容模型只有「策略枚举 + 有序目标列表」两项，理由见 {@link RoutingStrategy}。
 * 目标列表<b>不含版本号</b>：目标的固定版本由父 Agent 的 {@code childAgentRefs}
 * 提供 —— 版本真相只有一份（发布期校验会强制策略目标是拓扑的子集）。
 *
 * <p>⚠️ <b>标识是可读字符串，不是 UUID</b>：对外 schema 契约
 * （{@code common.schema.json#/$defs/id}）把 {@code fixedVersionRef.id} 定义为
 * 可读字符串（形如 {@code orders-router}），治理通道也按字符串接收。
 * 参见 V23 迁移的教训：把新概念的 id 声明成 UUID 会让整笔发布事务在
 * 非 UUID id 上回滚，而这种缺陷在 mock 连接的单元测试里<b>永远不会暴露</b>。
 *
 * @param tenantId         租户标识
 * @param policyId         策略标识（逻辑 id · 可读字符串）
 * @param version          策略版本号（固定引用）
 * @param strategy         选择策略
 * @param targets          允许的目标 Agent 标识（有序 · 非空 · 不重复）
 * @param configFingerprint 内容指纹（判定「策略变了没有」）
 * @param schemaVersion    行格式版本
 * @param createdAt        创建时间
 * @param publishedAt      发布时间；{@code null} = 草稿（<b>不可被引用</b>）
 * @param lifecycleState   生命周期状态（§6.6）；{@code null} = <b>未记录</b>（≠ 不是 DEPRECATED）
 */
public record CapRoutingPolicyDef(
        @NonNull String tenantId,
        @NonNull String policyId,
        int version,
        @NonNull RoutingStrategy strategy,
        @NonNull List<String> targets,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt,
        @Nullable ConfigLifecycleState lifecycleState) {

    /**
     * 兼容构造器：<b>不带生命周期状态</b>的策略定义行（第二十五批之前的形态）。
     *
     * <p>⚠️ 缺省 = {@code lifecycleState == null}，语义是「<b>未记录</b>」，
     * <b>不是</b>「不是 DEPRECATED」—— 它与「已停用」是<b>两个不同的事实</b>。
     * 未记录时 {@link #isDeprecated()} 返回 {@code false}（存量行口径不变），
     * 但**引用判定仍以 {@code publishedAt} 为准**（见 {@link #isPublished()}）。
     */
    public CapRoutingPolicyDef(@NonNull String tenantId,
                                @NonNull String policyId,
                                int version,
                                @NonNull RoutingStrategy strategy,
                                @NonNull List<String> targets,
                                @NonNull String configFingerprint,
                                int schemaVersion,
                                @NonNull Instant createdAt,
                                @Nullable Instant publishedAt) {
        this(tenantId, policyId, version, strategy, targets, configFingerprint, schemaVersion,
                createdAt, publishedAt, null);
    }

    public CapRoutingPolicyDef {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须为正数，收到: " + version);
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为空");
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (targets.isEmpty()) {
            // ⚠️ 空目标集合 ≠ 「允许任意」：策略必须**显式**声明允许哪些目标，
            //    否则「填坏了」与「没填」在存储层就分不开，
            //    而一个「允许任意目标」的策略等于没有策略。
            throw new IllegalArgumentException("targets 不能为空（策略必须显式声明允许的目标）");
        }
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("targets 元素不能为空");
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            for (int j = i + 1; j < targets.size(); j++) {
                if (targets.get(i).equals(targets.get(j))) {
                    // ⚠️ 重复目标会让「有序降级」的首选变得不确定，
                    //    也会让「策略允许了几个目标」这个计数失真。
                    throw new IllegalArgumentException("targets 元素重复: " + targets.get(i));
                }
            }
        }
        if (configFingerprint == null || configFingerprint.isBlank()) {
            throw new IllegalArgumentException("configFingerprint 不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }

    /**
     * @return 是否为已发布版本（草稿不可被 Agent 引用）
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * @return 是否已被<b>停用</b>（{@code DEPRECATED}）
     *
     * <p>⚠️ 「未记录」不等于「未停用」，但也不等于「已停用」：
     * {@code lifecycleState == null} 表示「这条记录没有携带生命周期状态」。
     * 引用判定必须同时看 {@link #isPublished()} 与本方法。
     */
    public boolean isDeprecated() {
        return lifecycleState == ConfigLifecycleState.DEPRECATED;
    }

    /**
     * @return 固定版本引用（{@code id@version}）
     */
    public @NonNull String ref() {
        return policyId + "@" + version;
    }

    /**
     * @param targetAgentId 候选目标 Agent 标识
     * @return 该目标是否落在本策略允许的目标集合内
     */
    public boolean allows(@NonNull String targetAgentId) {
        return targets.contains(targetAgentId);
    }
}
