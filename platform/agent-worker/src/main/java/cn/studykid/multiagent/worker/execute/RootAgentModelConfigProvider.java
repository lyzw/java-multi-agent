package cn.studykid.multiagent.worker.execute;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;
import cn.studykid.multiagent.ports.persistence.CapabilitySnapshotPort;

/**
 * 根 Agent 模型配置装配（多级 Agent v1.1 · 第二十一批 · FR-036）。
 *
 * <p>⚠️ <b>修复前的真实缺陷</b>：根 Agent 的模型标识 / 提示词 / 普通工具表
 * 此前是<b>硬编码占位</b>（{@code fake-model} + 写死的提示词 + 空工具表）。
 * 后果不是「配置不准」，而是
 * <b>根 Agent 实际上没有任何普通工具可用</b> ——
 * 它能看见的只有第二十批补上的委派工具。
 * 于是「多级 Agent 可用」这句话在根这一层是假的：
 * 根只能委派、不能自己干活。
 *
 * <p>⚠️ <b>根 Agent 身份来自 Run 能力快照</b>：
 * {@code execution_capability_snapshot} 记的是
 * 「<b>这个 Run 由哪个 Agent 的哪个 release 执行</b>」——
 * 它是根 Agent 身份的权威来源，而不是「当前已发布版本」
 * （后者会让在途 Run 随别人发布新版本而漂移，MA-T08）。
 *
 * <p>⚠️ <b>绑定固定 release</b>：装配按快照里的 {@code agentVersion} 精确取那一版，
 * <b>不解析「当前已发布版本」</b>。
 *
 * <p>⚠️ <b>读不到即 fail-closed</b>：拿不到根 Agent 的模型配置 → 返回空，
 * 由调用方<b>拒绝执行该 Run</b>。
 * 绝不回落 {@code fake-model} 占位 ——
 * 回落等于让「配置缺失」被伪装成「配置正常」，
 * 那正是本批要消除的假象。
 */
public class RootAgentModelConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(
            RootAgentModelConfigProvider.class);

    private final CapabilitySnapshotPort snapshots;
    private final AgentModelConfigAssembler assembler;
    private final DelegationToolSchemaAssembler delegationSchemas;

    /**
     * @param snapshots        Run 能力快照仓储（根 Agent 身份的权威来源）
     * @param assembler        按固定版本装配模型配置的共用组件
     * @param delegationSchemas 委派工具 Schema 装配器（可空 = 不装配委派工具）
     */
    public RootAgentModelConfigProvider(
            @NonNull CapabilitySnapshotPort snapshots,
            @NonNull AgentModelConfigAssembler assembler,
            @org.jspecify.annotations.Nullable DelegationToolSchemaAssembler
                    delegationSchemas) {
        this.snapshots = snapshots;
        this.assembler = assembler;
        this.delegationSchemas = delegationSchemas;
    }

    /**
     * 装配该 Run 的根 Agent 模型配置。
     *
     * @param tenantId 租户标识
     * @param runId    Run 标识
     * @return 根 Agent 模型配置；<b>读不到时返回空</b>（fail-closed）
     */
    public @NonNull Optional<RootAgentModelConfig> configFor(@NonNull String tenantId,
                                                              @NonNull String runId) {
        Optional<CapabilitySnapshot> snapshot;
        try {
            snapshot = snapshots.find(tenantId, runId);
        } catch (RuntimeException e) {
            log.warn("Run 能力快照读取失败，根 Agent 配置不可用: run={}", runId, e);
            return Optional.empty();
        }
        if (snapshot.isEmpty()) {
            // ⚠️ 不知道根 Agent 是谁 → 无法确定它跑在哪个模型上 → 拒绝执行。
            log.warn("Run 能力快照缺失，根 Agent 配置不可用: run={}", runId);
            return Optional.empty();
        }
        CapabilitySnapshot s = snapshot.get();

        Optional<AgentModelConfigAssembler.AgentModelSettings> settings =
                assembler.assemble(tenantId, s.agentId(), s.agentVersion());
        if (settings.isEmpty()) {
            // ⚠️ 取不到模型标识 → 拒绝执行（不猜、不回落占位模型）。
            log.warn("根 Agent 模型配置不可用: agent={}, version={}",
                    s.agentId(), s.agentVersion());
            return Optional.empty();
        }
        AgentModelConfigAssembler.AgentModelSettings base = settings.get();

        Map<String, Object> schemas = new LinkedHashMap<>(base.toolSchemas());
        // ⚠️ 委派工具与闭包工具<b>并列</b>：它不属于闭包，但同样出现在模型可见的工具表里。
        schemas.putAll(delegationToolSchema(tenantId, s.agentId(), s.agentVersion()));

        return Optional.of(new RootAgentModelConfig(
                s.agentId(), s.agentVersion(),
                base.modelId(), base.systemPrompt(), base.temperature(),
                base.maxTokens(), base.timeout(), schemas));
    }

    /**
     * 装配委派工具 Schema（<b>按根 Agent 自己的编排声明</b>）。
     *
     * <p>⚠️ <b>与子路径共用同一份实现</b>（{@link DelegationToolSchemaAssembler}）：
     * 两处各写一份必然漂移，而漂移的后果是
     * 「根能委派、子不能」（或反之）—— 那正是「两套语义」本身。
     */
    private @NonNull Map<String, Object> delegationToolSchema(@NonNull String tenantId,
                                                               @NonNull String agentId,
                                                               int agentVersion) {
        if (delegationSchemas == null) {
            // ⚠️ 未装配委派工具装配器 → 无从判断委派能力 → 不给委派工具。
            log.warn("未装配委派工具 Schema 装配器，根 Agent 不装配委派工具: agent={}", agentId);
            return Map.of();
        }
        return delegationSchemas.assemble(tenantId, agentId, agentVersion);
    }

    /**
     * 根 Agent 的模型配置。
     *
     * @param agentId      根 Agent 标识
     * @param agentVersion 根 Agent 的<b>固定 release</b>
     * @param modelId      模型标识
     * @param systemPrompt 系统提示
     * @param temperature  采样温度
     * @param maxTokens    最大输出 token
     * @param timeout      单次模型调用超时
     * @param toolSchemas  工具 Schema（按根 Agent <b>自己</b>的闭包 + 委派工具）
     */
    public record RootAgentModelConfig(
            @NonNull String agentId,
            int agentVersion,
            @NonNull String modelId,
            @NonNull String systemPrompt,
            double temperature,
            int maxTokens,
            Duration timeout,
            @NonNull Map<String, Object> toolSchemas) {

        public RootAgentModelConfig {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agentId 不能为空");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId 不能为空");
            }
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        }
    }
}
