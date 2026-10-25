package cn.studykid.multiagent.ports.orchestration;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 子 Agent 模型配置提供端口（多级 Agent v1.1 · P2-MA-02）。
 *
 * <p>⚠️ <b>子 Agent 的配置必须绑定<b>固定版本</b></b>：委派目标在发布时已固定 release，
 * 子执行必须用<b>那个版本</b>的模型配置 ——
 * 否则子 Agent 发布新版本后，在途委派会静默漂移到新模型/新提示词。
 */
public interface ChildAgentModelConfigProvider {

    /**
     * 读取子 Agent 指定版本的模型配置。
     *
     * @param tenantId     租户标识
     * @param agentId      子 Agent 标识
     * @param agentVersion 子 Agent 的<b>固定版本号</b>
     * @return 模型配置；该版本不可用时返回 {@link Optional#empty()}
     */
    @NonNull Optional<ChildModelConfig> configFor(@NonNull String tenantId,
                                                  @NonNull String agentId,
                                                  int agentVersion);

    /**
     * 子 Agent 的模型配置。
     *
     * @param modelId      模型标识
     * @param systemPrompt 系统提示（子 Agent 自己的，<b>不继承父的</b>）
     * @param temperature  采样温度
     * @param maxTokens    最大输出 token
     * @param timeout      单次模型调用超时
     * @param toolSchemas  工具 Schema（<b>按子 Agent 自己的闭包</b>给出）
     */
    record ChildModelConfig(
            @NonNull String modelId,
            @NonNull String systemPrompt,
            double temperature,
            int maxTokens,
            @NonNull Duration timeout,
            @NonNull Map<String, Object> toolSchemas) {

        public ChildModelConfig {
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId 不能为空");
            }
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            toolSchemas = toolSchemas == null ? Map.of() : Map.copyOf(toolSchemas);
        }
    }
}
