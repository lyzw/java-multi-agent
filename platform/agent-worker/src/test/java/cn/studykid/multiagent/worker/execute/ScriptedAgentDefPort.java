package cn.studykid.multiagent.worker.execute;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * 可编程的 Agent 定义仓储替身（2026-11-09 · 权威源定 A）。
 *
 * <p>⚠️ <b>它替代的是旧的 {@code AgentQueryPort} 替身</b>：
 * 权威源定为 {@code cap_agent_def} 后，运行期的提示词 / 模型 / 生成参数
 * 一律从 {@link CapAgentDefPort} 读 ——
 * 旧表 {@code agent_version.definition} 降级为归档，不再是配置真源。
 *
 * <p>⚠️ <b>版本必须精确匹配</b>：{@code find} 传入的版本与夹具版本不符时返回空，
 * 以便用例能断言「固定 release 取不到 → 拒绝装配」（MA-T08）。
 */
final class ScriptedAgentDefPort implements CapAgentDefPort {

    /** 模型引用；{@code null} = 未绑定模型（运行期 fail-closed）。 */
    @Nullable String modelRef = "model-1";

    /** 提示词声明 JSON 文本。 */
    String instructions = "{\"inlineInstructions\":\"你是辅导助手\"}";

    /** 生成参数 JSON 文本。 */
    String generationParams = "{}";

    /** 夹具里的版本号。 */
    int version = 1;

    /** 读库时抛异常（模拟仓储故障）。 */
    boolean throwOnFind = false;

    /** 是否存在该版本行。 */
    boolean present = true;

    @Override
    public boolean insert(@NonNull CapAgentDef def) {
        return true;
    }

    @Override
    public @NonNull Optional<CapAgentDef> find(@NonNull String tenantId,
                                                @NonNull String agentId,
                                                int version) {
        if (throwOnFind) {
            throw new IllegalStateException("仓储故障");
        }
        if (!present || version != this.version) {
            return Optional.empty();
        }
        return Optional.of(new CapAgentDef(
                tenantId, agentId, version,
                agentId, "描述", List.of("student"), List.of(), List.of(), List.of(),
                "{}", "{}", instructions, modelRef, generationParams,
                "fp", 1, java.time.Instant.EPOCH, java.time.Instant.EPOCH));
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatest(@NonNull String tenantId,
                                                      @NonNull String agentId) {
        return find(tenantId, agentId, version);
    }

    @Override
    public @NonNull Optional<CapAgentDef> findLatestPublished(@NonNull String tenantId,
                                                                 @NonNull String agentId) {
        return find(tenantId, agentId, version);
    }

    @Override
    public @NonNull List<CapAgentDef> listLatestPerAgent(@NonNull String tenantId) {
        return List.of();
    }
}
