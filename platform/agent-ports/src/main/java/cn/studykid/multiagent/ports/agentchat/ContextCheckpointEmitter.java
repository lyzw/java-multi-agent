package cn.studykid.multiagent.ports.agentchat;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelMessage;

/**
 * L2 SSE 轮末 <b>client carry-over 压缩态封包</b>的中性端口（ADR-013 D49 / S3-M8-b-2）。
 *
 * <p>⚠️ <b>为什么单独成端口、放 {@code ports/agentchat}</b>：D50 已把
 * {@code ports/context} 的四接口目录冻结为 RV-65 守卫面，发射侧与接收侧
 * （{@link ContextStateVerifier}）对称地放在 {@code ports/agentchat}。worker 只认识
 * 本端口的<b>中性 DTO</b>，不碰 {@code agent-context} 的 {@code CheckpointDelta} /
 * {@code ContextStateEnvelope} / {@code StructuredState}（ADR-011 / C1）。
 * 真正的封包算法由组合根 adapter 委托纯算法层，本端口只钉死契约。
 *
 * <p>⚠️ <b>返回 null = 本轮不发射</b>：无新增可吸收轮次、抽取回落、状态超长、
 * 密钥不可用、policyVersion 未裁决等一切「不该发 / 发了也会被接收侧六项丢弃」的情形
 * 一律返回 {@code null}。⛔ 绝不为凑事件而发空壳 checkpoint，也绝不因可选增强失败
 * 而中断 RAG / 回答 / 审计主流程——这是 fail-soft 的<b>可选轮末增强</b>，
 * 与装配入口的 fail-closed（会拒绝对话）边界不同。
 *
 * <p>⚠️ <b>policyVersion 由调用方注入</b>（D17）：必须与接收侧同一份已裁决指纹
 * （{@link ContextPolicyVersionPort}），⛔ 端口/adapter 不自己拼全局常量、不读算法层配置。
 *
 * <p>⚠️ <b>纯端口</b>：不引用算法层类型、零 Spring / 零 JDBC / 零 IO；全参构造唯一，
 * 不提供兼容构造器（铁律 #10）。
 */
public interface ContextCheckpointEmitter {

    /**
     * 尝试为本轮生成可发射的压缩态。
     *
     * @param input 冻结身份 / 已验上一轮状态 / 本轮历史 / 装配态事实
     * @return 可直接映射成 {@code context.checkpoint} 事件的中性载荷；
     *         {@code null} = 本轮不发射（静默、无副作用）
     */
    @Nullable Emitted emit(@NonNull Input input);

    /**
     * 全参输入。⛔ TTL / max-state-chars / HMAC key / 时钟由组合根 adapter 持有，
     * 不进本 DTO——worker 不读算法层配置，避免「配了却不生效」的第二套真相。
     *
     * @param tenantId           租户 id（封 tenant digest，不放明文）
     * @param scopeKey           作用域键（SSE 用 {@code chat:<runId>}）
     * @param modelId            目标模型（加载时须一致）
     * @param historyMessages    本轮可见历史（顺序即对话顺序；索引 + 1 作为 seq 坐标）
     * @param historyTurns       本轮历史轮次（写入 {@code upToTurn}，接收侧校验其 ≤ 下一轮 history）
     * @param policyVersion      D17 已裁决策略指纹
     * @param carryOverStateJson 上一轮<b>已验</b>状态 JSON；null = 无
     * @param tier               触发档位 1/2/3（W1/W2/W3；W0 不发本事件）
     * @param fallback           是否 W3 回落 W2
     */
    record Input(
            @NonNull String tenantId,
            @NonNull String scopeKey,
            @NonNull String modelId,
            @NonNull List<ModelMessage> historyMessages,
            int historyTurns,
            @NonNull String policyVersion,
            @Nullable String carryOverStateJson,
            int tier,
            boolean fallback) {

        public Input {
            require(tenantId, "tenantId");
            require(scopeKey, "scopeKey");
            require(modelId, "modelId");
            require(policyVersion, "policyVersion");
            if (historyTurns < 0) {
                throw new IllegalArgumentException("ContextCheckpointEmitter.Input.historyTurns 不得为负");
            }
            if (tier < 1 || tier > 3) {
                throw new IllegalArgumentException(
                        "ContextCheckpointEmitter.Input.tier 必须是 1/2/3（W0 不发射）");
            }
            historyMessages = historyMessages == null ? List.of() : List.copyOf(historyMessages);
        }

        private static void require(@Nullable String value, @NonNull String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "ContextCheckpointEmitter.Input." + field + " 不得为空（fail-closed）");
            }
        }
    }

    /**
     * 可发射的中性载荷。字段与 {@code AgentChatEvent.ContextCheckpoint} 一一对应；
     * {@code state} 是<b>不透明 HMAC 信封</b>，worker 不解析、不解码、不当指令执行。
     *
     * @param state          不透明 base64 压缩态信封
     * @param upToTurn       已吸收轮次；null = 省略（接收侧据此做第 6 项游标校验）
     * @param policyVersion  策略指纹（与 Done 版本同源的当前策略）
     * @param tier           触发水位 1/2/3
     * @param fallback       是否回落
     * @param digest         状态摘要 sha256:…
     */
    record Emitted(
            @NonNull String state,
            @Nullable Integer upToTurn,
            @NonNull String policyVersion,
            int tier,
            boolean fallback,
            @NonNull String digest) {

        public Emitted {
            require(state, "state");
            require(policyVersion, "policyVersion");
            require(digest, "digest");
            if (upToTurn != null && upToTurn < 0) {
                throw new IllegalArgumentException("Emitted.upToTurn 不得为负");
            }
            if (tier < 1 || tier > 3) {
                throw new IllegalArgumentException("Emitted.tier 必须是 1/2/3");
            }
        }

        private static void require(@Nullable String value, @NonNull String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("ContextCheckpointEmitter.Emitted." + field + " 不得为空");
            }
        }
    }
}
