package cn.studykid.multiagent.bootstrap.config;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentChatAuditRepository;
import cn.studykid.multiagent.infrastructure.rag.HttpRagRetrievalGateway;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe;
import cn.studykid.multiagent.worker.agentchat.AgentChatOrchestrator;
import cn.studykid.multiagent.worker.agentchat.AgentRouter;
import cn.studykid.multiagent.worker.agentchat.ModelAgentRouter;
import cn.studykid.multiagent.worker.execute.AgentModelConfigAssembler;
import cn.studykid.multiagent.worker.execute.ToolInvocationGate;

/**
 * 请求级通用 Agent 编排对话的组合根（双端点 SSE 的生产装配）。
 *
 * <p><b>为什么单独立一个类</b>：这条链路横跨 5 个模块（ports / infrastructure /
 * persistence / worker / api），把装配塞进已经两千多行的 {@link ApplicationConfiguration}
 * 会让「谁接了真实实现」无法审阅。本类刻意独立，职责只有一件事：
 * 把 {@code agent-api} 控制器依赖的两个端口，接到<b>真实</b>实现上。
 *
 * <p><b>绝不装配桩</b>：RAG 网关、审计仓储、路由器、编排器全部注入真实实现。
 * 任何一处退回 fake / noop，表现都是「页面流式正常、RAG/工具/审计其实没跑」
 * ——这正是本项目反复要消除的静默失真。
 *
 * <p><b>装配面的三条硬约束</b>：
 * <ol>
 *   <li>{@link AgentChatAuditPort} 走主数据源 {@code app_agent}（NOBYPASSRLS），
 *       两段式写 {@code run} / {@code run_event} / {@code run_step}，
 *       <b>不写 outbox_event</b>；仓储刻意不挂 {@code @Repository}，只由本类显式注册。</li>
 *   <li>{@link RagRetrievalPort} 是 platform → rag 独立进程的 HTTP 适配器，
 *       base-url 来自配置；令牌由身份解析器从已验签请求头透传。</li>
 *   <li>{@link AgentChatOrchestrator} 复用与 Worker <b>同一个</b>
 *       {@link ToolInvocationGate} / {@link ClosureResolver} / 模型配置装配器，
 *       绝不另造一套「对话专用」的权限或工具语义。</li>
 * </ol>
 *
 * <p><b>多构造纪律</b>：{@code AgentChatOrchestrator} 与 {@code AgentChatController}
 * 都有测试用全参构造，生产入口由本类显式 {@code new} 指定，不依赖 Spring 猜构造器。
 */
@Configuration
public class AgentChatConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentChatConfiguration.class);

    /** 与 application.yml 的 {@code agent.rag.base-url} 同源（含环境变量覆盖）。 */
    private static final String RAG_BASE_URL_KEY = "agent.rag.base-url";
    private static final String RAG_BASE_URL_DEFAULT = "http://127.0.0.1:8081";

    /** 路由模型与控制台对话共用同一个默认模型配置键，避免两处漂移。 */
    private static final String DEFAULT_MODEL_KEY = "agent.model.minimax.default-model";
    private static final String DEFAULT_MODEL = "MiniMax-M2.5";

    /**
     * RAG 检索网关（platform → rag 独立服务 {@code POST /api/v1/retrieval}）。
     *
     * <p>⚠️ 没有默认值以外的「假检索」：地址配错时 rag 不可达，编排器如实发组件级
     * {@code skill.phase=error} + 空命中，**不伪造命中**。
     */
    @Bean
    public RagRetrievalPort ragRetrievalPort(
            @Value("${" + RAG_BASE_URL_KEY + ":" + RAG_BASE_URL_DEFAULT + "}")
            @NonNull String baseUrl) {
        log.info("装配 RAG 检索网关: baseUrl={}", baseUrl);
        return new HttpRagRetrievalGateway(baseUrl);
    }

    /**
     * 请求级对话审计仓储（两段式 RUNNING → 终态）。
     *
     * <p>⚠️ 不挂 {@code @Repository}：与 {@code executionSnapshotPort} /
     * {@code runClaimPort} 同一纪律，避免测试切片的组件扫描意外拾取后
     * 出现「两个同类型 Bean」或「测试里用了未绑租户的连接」。
     */
    @Bean
    public AgentChatAuditPort agentChatAuditPort(@NonNull DataSource dataSource) {
        return new JdbcAgentChatAuditRepository(dataSource);
    }

    /**
     * 入口级 Agent 路由（显式 agentId > 单候选直选 > 模型语义选择，失败 fail-closed）。
     *
     * <p>复用控制台对话网关但不复用其「思考草稿对外可见」开关：
     * 路由中间产物永远不向用户展示，由 {@link ModelAgentRouter} 内部丢弃。
     */
    @Bean
    public AgentRouter agentRouter(
            @Qualifier("chatStreamGateway") @NonNull ChatStreamGateway chatGateway,
            @Value("${" + DEFAULT_MODEL_KEY + ":" + DEFAULT_MODEL + "}")
            @NonNull String defaultModel) {
        return new ModelAgentRouter(chatGateway, defaultModel);
    }

    /** 身份解析器（组合根独占 SecurityConfiguration 的请求属性常量）。 */
    @Bean
    public AgentChatIdentityResolver agentChatIdentityResolver(
            @NonNull TenantLifecycleProbe lifecycleProbe) {
        return new RequestAgentChatIdentityResolver(lifecycleProbe);
    }

    /**
     * 请求级流式编排器：一次 HTTP 请求推完 13 类事件并落 run 审计。
     *
     * <p>⚠️ 全部依赖必须真实注入。特别是：
     * <ul>
     *   <li>{@code AgentQueryPort} 提供候选（RLS 内查询），与治理面同一份数据；</li>
     *   <li>{@code ExecutionSnapshotPort} 提供固定版本的执行快照，
     *       保证工具账本外键与「在途 Run 不漂移」；</li>
     *   <li>{@code ToolInvocationGate} 是工具执行的唯一闸门（审批 / 预算 / 闭包 / 取消
     *       全在里面），<b>绝不允许</b>对话链路绕过它直接调工具。</li>
     * </ul>
     */
    @Bean
    public AgentChatOrchestratorPort agentChatOrchestrator(
            @NonNull AgentChatAuditPort audit,
            @NonNull RagRetrievalPort rag,
            @Qualifier("chatStreamGateway") @NonNull ChatStreamGateway chatGateway,
            @NonNull AgentRouter router,
            @NonNull AgentQueryPort agents,
            @NonNull ExecutionSnapshotPort snapshots,
            @NonNull ClosureResolver closureResolver,
            @NonNull CapAgentDefPort agentDefs,
            @NonNull CapToolDefPort toolDefs,
            @NonNull ModelResolver modelResolver,
            @NonNull ToolInvocationGate toolGate) {
        AgentModelConfigAssembler modelConfig = new AgentModelConfigAssembler(
                agentDefs, closureResolver, toolDefs, modelResolver);
        log.info("装配请求级 Agent 编排器: 路由/RAG/Skill/Tool/审计均已接真实实现"
                + "（不写 Outbox，lease_owner={}）",
                cn.studykid.multiagent.ports.persistence.RunClaimPort.SSE_DIRECT_LEASE_OWNER);
        return new AgentChatOrchestrator(audit, rag, chatGateway, router, agents,
                snapshots, closureResolver, modelConfig, toolGate);
    }
}
