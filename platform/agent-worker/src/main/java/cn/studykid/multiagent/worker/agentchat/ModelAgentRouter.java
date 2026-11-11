package cn.studykid.multiagent.worker.agentchat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.studykid.multiagent.ports.agentchat.AgentChatEvent;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelMessage;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;

/**
 * 入口级 Agent 路由：显式指定 > 单候选直选 > 模型语义选择。
 *
 * <p><b>语义选择是真实的</b>：把候选 Agent 的 id / 名称 / 描述放进一条
 * 无工具的模型调用，要求只回一个 JSON 对象；随后<b>严格白名单校验</b>
 * 返回的 agentId 必须命中候选集，否则 fail-closed。
 * 绝不从自由文本里「猜」Agent，也不在模型失败时静默回落到第一个候选
 * ——那会把「路由没生效」伪装成「路由选对了」。
 *
 * <p><b>单候选直选</b>不是降级：只有一个可执行 Agent 时不存在选择空间，
 * 省一次模型调用，理由如实写明。
 *
 * <p><b>候选来源</b>由编排器保证是「已发布且有执行快照」的 Agent；本类只做选择。
 */
public class ModelAgentRouter implements AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelAgentRouter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 从模型自由文本里兜底捞 JSON 对象（模型偶尔会包 ```json 围栏）。 */
    private static final Pattern JSON_OBJECT =
            Pattern.compile("\\{[^{}]*\\}", Pattern.DOTALL);

    private static final String ROUTER_SYSTEM = """
            你是 Agent 路由选择器。只能从给定的候选 Agent 中选出一个最适合承接用户请求的 Agent。
            输出严格 JSON：{"agentId":"<候选里的完整 id>","reason":"<一句话理由>"}
            不得输出候选项之外的 agentId，不得输出 JSON 以外的任何文字。
            """;

    private static final int ROUTER_MAX_TOKENS = 192;
    private static final Duration ROUTER_TIMEOUT = Duration.ofSeconds(15);

    private final ChatStreamGateway chatGateway;
    private final String defaultModel;

    public ModelAgentRouter(@NonNull ChatStreamGateway chatGateway,
                            @NonNull String defaultModel) {
        this.chatGateway = chatGateway;
        this.defaultModel = defaultModel == null || defaultModel.isBlank()
                ? "MiniMax-M2.5" : defaultModel;
    }

    @Override
    public @NonNull Decision route(@NonNull String tenantId,
                                   @NonNull String message,
                                   @Nullable String explicitAgentId,
                                   @NonNull List<AgentQueryPort.AgentItem> candidates) {
        List<AgentChatEvent.Candidate> view = candidates.stream()
                .map(c -> new AgentChatEvent.Candidate(
                        c.agentId(), c.name() == null || c.name().isBlank() ? c.agentId() : c.name(),
                        null, c.description()))
                .toList();

        if (candidates.isEmpty()) {
            throw new RoutingException("该租户没有已发布且可执行的 Agent，无法路由");
        }
        if (explicitAgentId != null && !explicitAgentId.isBlank()) {
            String target = explicitAgentId.trim();
            return candidates.stream()
                    .filter(c -> target.equals(c.agentId()))
                    .findFirst()
                    .map(c -> new Decision(c.agentId(),
                            "调用方显式指定 AgentId", view))
                    .orElseThrow(() -> new RoutingException(
                            "显式指定的 AgentId 不在可执行候选集中: " + target));
        }
        if (candidates.size() == 1) {
            AgentQueryPort.AgentItem only = candidates.get(0);
            return new Decision(only.agentId(),
                    "该租户仅有一个已发布 Agent，无需模型选择", view);
        }

        String raw = askModel(message, candidates);
        Parsed parsed = parse(raw);
        return candidates.stream()
                .filter(c -> parsed.agentId().equals(c.agentId()))
                .findFirst()
                .map(c -> new Decision(c.agentId(),
                        parsed.reason().isBlank() ? "模型语义路由" : "模型语义路由：" + parsed.reason(),
                        view))
                .orElseThrow(() -> new RoutingException(
                        "模型返回的 agentId 不在候选白名单内（已 fail-closed）: "
                                + parsed.agentId()));
    }

    /** 模型路由调用：无工具、低 token、短超时，失败即 fail-closed。 */
    private @NonNull String askModel(@NonNull String message,
                                     @NonNull List<AgentQueryPort.AgentItem> candidates) {
        StringBuilder sb = new StringBuilder("候选 Agent：\n");
        for (AgentQueryPort.AgentItem c : candidates) {
            sb.append("- agentId=").append(c.agentId())
                    .append(" name=").append(c.name())
                    .append(" description=")
                    .append(c.description() == null ? "(无描述)" : c.description())
                    .append('\n');
        }
        sb.append("\n用户请求：").append(message);

        ModelRequest request = new ModelRequest(
                defaultModel, ROUTER_SYSTEM, "", 0.0, ROUTER_MAX_TOKENS,
                ROUTER_TIMEOUT, Map.of(), List.of(ModelMessage.user(sb.toString())));
        try {
            ModelResponse response = chatGateway.streamChat(request,
                    // 路由中间产物不向用户展示，绝不桥接 delta / thinking。
                    token -> { });
            return response.content() == null ? "" : response.content();
        } catch (ModelGatewayException e) {
            throw new RoutingException("模型路由调用失败: " + e.kind().name(), e);
        } catch (RuntimeException e) {
            throw new RoutingException("模型路由调用异常", e);
        }
    }

    /** 严格解析：先整串 JSON，再退而取文本里的第一个 {...} 对象。 */
    private static @NonNull Parsed parse(@NonNull String raw) {
        String text = raw.trim();
        List<String> candidates = new ArrayList<>();
        candidates.add(text);
        Matcher matcher = JSON_OBJECT.matcher(text);
        while (matcher.find()) {
            candidates.add(matcher.group());
        }
        for (String candidate : candidates) {
            try {
                JsonNode node = JSON.readTree(candidate);
                if (node == null || !node.isObject()) {
                    continue;
                }
                JsonNode id = node.get("agentId");
                if (id == null || !id.isString() || id.asString().isBlank()) {
                    continue;
                }
                JsonNode reason = node.get("reason");
                String reasonText = reason != null && reason.isString()
                        ? reason.asString() : "";
                // ⚠️ 理由会进 SSE 事件，必须限长 + 去换行，避免模型夹带指令样式文本。
                reasonText = reasonText.replace('\r', ' ').replace('\n', ' ').trim();
                if (reasonText.length() > 200) {
                    reasonText = reasonText.substring(0, 200);
                }
                return new Parsed(id.asString().trim(), reasonText);
            } catch (RuntimeException ignored) {
                // 继续尝试下一个候选片段
            }
        }
        throw new RoutingException("模型路由输出不是合法 JSON（已 fail-closed）: "
                + preview(text));
    }

    /** 日志/异常里只留有限前缀，避免把用户输入整段带进日志。 */
    private static @NonNull String preview(@NonNull String text) {
        String flat = text.replace('\r', ' ').replace('\n', ' ');
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private record Parsed(@NonNull String agentId, @NonNull String reason) {
    }
}
