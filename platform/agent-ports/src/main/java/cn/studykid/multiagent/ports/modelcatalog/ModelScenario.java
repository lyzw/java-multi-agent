package cn.studykid.multiagent.ports.modelcatalog;

import java.util.Set;

/**
 * 模型使用场景（模型统一配置 · 管理后台集中维护）。
 *
 * <p><b>为什么是字符串常量而非穷举枚举</b>：场景由管理后台<b>按业务自由定义</b>
 * （如「对话」「推理」「向量化」「摘要」「分类」「内容审核」），
 * 若锁死成枚举，新增场景就必须改代码发版——这与「配置由后台集中维护、不分散定义」的目标相悖。
 * 故场景以字符串 code 存储，本类只固化<b>平台强依赖的少数内置场景</b>作为约定值，
 * 其余场景由 admin 自定义，解析器不关心场景字面量、只按 code 匹配。
 *
 * <p><b>⚠️ 默认场景的语义</b>：{@link #DEFAULT} 是兜底场景——
 * 当某具体场景没有命中模型时，解析器回落到 {@code default} 场景的默认模型，
 * 保证「任何场景都至少有一个可用模型（若管理员配了的话）」。
 */
public final class ModelScenario {

    /** 主 Agent 对话（Worker 执行链路的核心 LLM 调用）。 */
    public static final String AGENT_CHAT = "agent-chat";

    /** 管理后台「直接对话」流式网关（控制台调试用）。 */
    public static final String CONSOLE_CHAT = "console-chat";

    /** 向量化 / Embedding（RAG 索引与查询共用）。 */
    public static final String EMBEDDING = "embedding";

    /** 摘要 / 归纳。 */
    public static final String SUMMARIZATION = "summarization";

    /** 分类 / 意图识别。 */
    public static final String CLASSIFICATION = "classification";

    /** 内容安全审核（输出护栏）。 */
    public static final String MODERATION = "moderation";

    /** 兜底默认场景：其他场景无命中时回落到此处的默认模型。 */
    public static final String DEFAULT = "default";

    /** 平台强依赖的内置场景（admin 仍可在这些之外自定义）。 */
    private static final Set<String> BUILTIN = Set.of(
            AGENT_CHAT, CONSOLE_CHAT, EMBEDDING, SUMMARIZATION, CLASSIFICATION, MODERATION, DEFAULT);

    private ModelScenario() {
    }

    /** @return 是否为平台内置场景（仅用于展示标识，不影响解析）。 */
    public static boolean isBuiltin(@org.jspecify.annotations.Nullable String code) {
        return code != null && BUILTIN.contains(code);
    }
}
