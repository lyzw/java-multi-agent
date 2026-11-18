package cn.studykid.multiagent.ports.invocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 能力注册表条目：一个能力声明一次，两条入口共用。
 *
 * <p><b>声明式安全</b>：把"哪个能力允许哪些入口、哪些作用域、是否必须票据、数据敏感度"
 * 写在能力定义里，而不是散落在各页面 / 控制器。Agent 代为调用时先查注册表，能力不在
 * allowedChannels 或 scope 不合法即在工具调用前拒绝，不靠 Agent 自觉。
 *
 * @param id               能力标识，如 rag.corpus.ingest / agent.chat.orchestrate
 * @param allowedChannels  允许的入口集合
 * @param allowedScopes    允许的作用域集合
 * @param requiresTicket   是否需要票据（G 档写 / Agent 代写）
 * @param sensitiveContent 是否触及业务正文（A 档恒不出正文的红线依据）
 * @param description      人读说明
 */
public record Capability(
        @NonNull String id,
        @NonNull Set<Channel> allowedChannels,
        @NonNull Set<Scope> allowedScopes,
        boolean requiresTicket,
        boolean sensitiveContent,
        @NonNull String description) {

    public Capability {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(allowedChannels, "allowedChannels");
        Objects.requireNonNull(allowedScopes, "allowedScopes");
        Objects.requireNonNull(description, "description");
        if (id.isBlank()) {
            throw new IllegalArgumentException("capability id 不得为空");
        }
        allowedChannels = Collections.unmodifiableSet(Set.copyOf(allowedChannels));
        allowedScopes = Collections.unmodifiableSet(Set.copyOf(allowedScopes));
    }

    /** RAG 语料录入：平台直录 + Agent 编排两入口；G/T 可写、A 只读。 */
    public static final Capability RAG_CORPUS_INGEST = new Capability(
            "rag.corpus.ingest",
            Set.of(Channel.PLATFORM_DIRECT, Channel.AGENT_ORCH),
            Set.of(Scope.PLATFORM, Scope.TENANT, Scope.ALL),
            true,
            true,
            "RAG 语料登记/上传/校对/校验决定；G 档写须治理票据，Agent 代写须代理票据");

    /** Agent 编排对话：平台链显式租户 + 租户链令牌租户；一律只读语料。 */
    public static final Capability AGENT_CHAT_ORCHESTRATE = new Capability(
            "agent.chat.orchestrate",
            Set.of(Channel.PLATFORM_DIRECT, Channel.TENANT_SESSION),
            Set.of(Scope.TENANT),
            false,
            true,
            "通用 Agent 编排对话；逐跳授权，租户不跨档，租户链恒不下发推理草稿");

    /** 运行执行：执行票证签发后的真实 Run，租户域最小角色。 */
    public static final Capability RUN_EXECUTE = new Capability(
            "run.execute",
            Set.of(Channel.PLATFORM_DIRECT, Channel.AGENT_ORCH),
            Set.of(Scope.TENANT),
            true,
            false,
            "发起一次真实 Run；执行必须指名 Agent，票证必须指名租户成员");

    /**
     * 冻结注册表：默认值为内置三能力，允许调用方以副本叠加自定义能力而不污染全局。
     * map 不可变，避免运行期被注入伪造能力。
     */
    public static @NonNull Map<String, Capability> defaults() {
        Map<String, Capability> map = new LinkedHashMap<>();
        map.put(RAG_CORPUS_INGEST.id(), RAG_CORPUS_INGEST);
        map.put(AGENT_CHAT_ORCHESTRATE.id(), AGENT_CHAT_ORCHESTRATE);
        map.put(RUN_EXECUTE.id(), RUN_EXECUTE);
        return Collections.unmodifiableMap(map);
    }

    /** 从注册表按 id 查找；未知能力返回 empty（调用方 fail-closed 拒绝）。 */
    public static @NonNull Optional<Capability> find(@NonNull Map<String, Capability> registry,
                                                      @Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.get(id));
    }
}
