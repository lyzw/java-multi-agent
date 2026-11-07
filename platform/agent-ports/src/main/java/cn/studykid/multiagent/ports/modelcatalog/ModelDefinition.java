package cn.studykid.multiagent.ports.modelcatalog;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 统一模型定义（模型配置的唯一事实来源，由管理后台集中维护）。
 *
 * <p><b>⚠️ 这是「配置」不是「凭据」</b>：本对象<b>不含 API Key</b>。
 * 凭据按 {@code provider} 从环境变量 / 密钥管理器解析
 * （见 {@code ModelCredentialProvider}），遵循项目铁律「api-key 仅环境变量注入，不得入库」。
 * 把密钥塞进本对象会在管理后台响应里泄漏——本项目对此零容忍。
 *
 * <p><b>字段约束</b>：
 * <ul>
 *   <li>{@code provider}：模型提供商（minimax / openai / deepseek / ollama / bge / workbuddy …），
 *       网关工厂据此选择适配器；</li>
 *   <li>{@code scenario}：使用场景（见 {@link ModelScenario}），同一场景可有多个模型；</li>
 *   <li>{@code modelName}：发给供应商 API 的真实模型标识（如 {@code MiniMax-M2.5}、{@code bge-m3}）；</li>
 *   <li>{@code isDefault}：该场景的默认模型（每场景至多一个，DB 以部分唯一索引强制）；</li>
 *   <li>{@code baseUrl}：供应商基础地址（对话类拼 {@code /chat/completions}，嵌入类拼 {@code /embeddings}）；</li>
 *   <li>{@code dimension}：仅嵌入模型需要，必须与向量列维度一致；</li>
 *   <li>{@code supportsTools/Streaming/Vision}：能力声明，运行时用于裁剪请求；</li>
 *   <li>{@code isEnabled}：软开关，关闭即不参与解析（fail-closed 不回落到已禁用模型）。</li>
 * </ul>
 *
 * <p><b>⚠️ 不可变值对象</b>：新增 / 更新由仓储统一处理；本类只做结构化与校验，
 * 不持有任何 Spring / JDBC 类型。
 */
public record ModelDefinition(
        /** 主键（新增时可为 {@code null}，由仓储生成）。 */
        @Nullable String id,
        /** 提供商（非空）。 */
        @NonNull String provider,
        /** 使用场景 code（非空）。 */
        @NonNull String scenario,
        /** 供应商模型标识（非空）。 */
        @NonNull String modelName,
        /** 展示名（可空，管理后台列表展示用）。 */
        @Nullable String displayName,
        /** 供应商基础地址（可空，缺省时由凭据解析器给默认值）。 */
        @Nullable String baseUrl,
        /** 是否为该场景默认模型。 */
        boolean isDefault,
        /** 是否支持工具调用。 */
        boolean supportsTools,
        /** 是否支持流式输出。 */
        boolean supportsStreaming,
        /** 是否支持视觉输入。 */
        boolean supportsVision,
        /** 向量维度（仅嵌入模型；对话模型为 {@code null}）。 */
        @Nullable Integer dimension,
        /** 是否启用。 */
        boolean isEnabled,
        /** 排序权重（同场景列表展示用）。 */
        int sortOrder,
        /** 备注（可空）。 */
        @Nullable String description,
        /** 创建时间（仓储回填）。 */
        @Nullable Instant createdAt,
        /** 更新时间（仓储回填）。 */
        @Nullable Instant updatedAt) {

    /** 校验非空字段；空值构造即抛，杜绝「半截配置」进入运行时。 */
    public ModelDefinition {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("模型 provider 不能为空");
        }
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("模型 scenario 不能为空");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("模型 modelName 不能为空");
        }
    }

    /** 返回安全的展示名：优先 {@code displayName}，否则回退 {@code modelName}。 */
    public @NonNull String label() {
        return (displayName == null || displayName.isBlank()) ? modelName : displayName;
    }
}
