package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.application.agentchataudit.AuditingAgentChatOrchestrator;
import cn.studykid.multiagent.application.cap.ClosureResolver;
import cn.studykid.multiagent.application.modelcatalog.ModelResolver;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.context.HeuristicTokenCounter;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAgentChatAuditRepository;
import cn.studykid.multiagent.infrastructure.rag.HttpRagRetrievalGateway;
import cn.studykid.multiagent.ports.agentchat.AgentChatAuditPort;
import cn.studykid.multiagent.ports.agentchat.AgentChatIdentityResolver;
import cn.studykid.multiagent.ports.agentchat.AgentChatOrchestratorPort;
import cn.studykid.multiagent.ports.agentchat.ContextCheckpointEmitter;
import cn.studykid.multiagent.ports.agentchat.ContextPolicyVersionPort;
import cn.studykid.multiagent.ports.agentchat.ContextStateVerifier;
import cn.studykid.multiagent.ports.agentchat.RagHitQuotaPort;
import cn.studykid.multiagent.ports.agentchat.RagRetrievalPort;
import cn.studykid.multiagent.ports.contextwiring.RequestAssemblyPort;
import cn.studykid.multiagent.ports.model.ChatStreamGateway;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ExecutionSnapshotPort;
import cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe;
import cn.studykid.multiagent.worker.agentchat.AgentChatOrchestrator;
import cn.studykid.multiagent.worker.agentchat.AgentRouter;
import cn.studykid.multiagent.worker.agentchat.ModelAgentRouter;
import cn.studykid.multiagent.worker.execute.ToolInvocationGate;

/**
 * 生产组合根「真接了实现」的守卫（关闭「页面流式正常、后端其实空转」的第三级验收）。
 *
 * <p><b>为什么不是 Spring 上下文测试</b>：完整上下文依赖真实 PG / 模型凭据，
 * 在单测环境无法稳定启动。这里直接调用 {@code @Bean} 方法，逐个断言返回<b>真实类型</b>
 * 而非 Fake/NoOp/Stub，并用反射确认编排器的每一个依赖都被注入。
 * 上下文能否启动由 IT 层覆盖。
 *
 * <p><b>反向验证</b>：把任一 {@code @Bean} 改成返回 stub / 删掉一个编排器依赖，
 * 对应断言立即红。
 */
class AgentChatConfigurationWiringTest {

    private final AgentChatConfiguration config = new AgentChatConfiguration();

    @Test
    @DisplayName("RAG 网关装配为真实 HTTP 适配器（不是桩 / NoOp）")
    void ragGatewayIsReal() {
        RagRetrievalPort port = config.ragRetrievalPort("http://127.0.0.1:8081");
        assertThat(port).isInstanceOf(HttpRagRetrievalGateway.class);
    }

    @Test
    @DisplayName("⛔ 审计仓储是真实 JDBC 实现，且刻意不挂 @Repository（防双 Bean 启动失败）")
    void auditRepositoryIsRealAndNotComponentScanned() {
        AgentChatAuditPort port = config.agentChatAuditPort(mock(DataSource.class));
        assertThat(port).isInstanceOf(JdbcAgentChatAuditRepository.class);
        assertThat(JdbcAgentChatAuditRepository.class.isAnnotationPresent(Repository.class))
                .as("组件扫描 + @Bean 双注册会让 AgentChatAuditPort 出现两个同类型 Bean → "
                        + "上下文启动失败")
                .isFalse();
    }

    @Test
    @DisplayName("路由与身份解析器装配为真实实现")
    void routerAndIdentityResolverAreReal() {
        assertThat(config.agentRouter(mock(ChatStreamGateway.class), "MiniMax-M2.5"))
                .isInstanceOf(ModelAgentRouter.class);
        assertThat(config.agentChatIdentityResolver(mock(TenantLifecycleProbe.class), ""))
                .isInstanceOf(RequestAgentChatIdentityResolver.class);
    }

    @Test
    @DisplayName("RAG 配额端口装配为真实组合根适配器（不是桩 / NoOp / 第二套算法）")
    void ragQuotaPortIsRealAdapter() {
        RagHitQuotaPort port = new RagHitQuotaConfiguration().ragHitQuotaPort(
                new AgentContextProperties(),
                GlobalContextPolicy.shadowDisabled(),
                new HeuristicTokenCounter());

        assertThat(port).isInstanceOf(RagHitQuotaAdapter.class);
    }

    @Test
    @DisplayName("⛔ 编排器的全部生产依赖都真实注入（缺一个即红）")
    void orchestratorReceivesEveryDependency() throws Exception {
        AgentChatOrchestratorPort port = config.agentChatOrchestrator(
                mock(AgentChatAuditPort.class),
                mock(RagRetrievalPort.class),
                mock(ChatStreamGateway.class),
                mock(AgentRouter.class),
                mock(AgentQueryPort.class),
                mock(ExecutionSnapshotPort.class),
                mock(ClosureResolver.class),
                mock(CapAgentDefPort.class),
                mock(CapToolDefPort.class),
                mock(ModelResolver.class),
                mock(ToolInvocationGate.class),
                mock(ContextStateVerifier.class),
                mock(ContextPolicyVersionPort.class),
                mock(RequestAssemblyPort.class),
                mock(ContextCheckpointEmitter.class),
                mock(RagHitQuotaPort.class));

        // 生产装配在编排器外面包了一层「对话审计装饰器」（只观察、不改写）；
        // 守卫要继续检查真实编排器的依赖注入，必须先解开这层包装。
        assertThat(port).isInstanceOf(AuditingAgentChatOrchestrator.class);
        AgentChatOrchestratorPort real = ((AuditingAgentChatOrchestrator) port).unwrap();
        assertThat(real).isInstanceOf(AgentChatOrchestrator.class);
        for (Field field : AgentChatOrchestrator.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            assertThat(field.get(real))
                    .as("编排器依赖 %s 不得为 null（否则运行期 NPE 或功能空转）", field.getName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("⛔ 应急熔断开时编排器返回原实例（不做审计包装）")
    void emergencyStopReturnsRawOrchestrator() throws Exception {
        AgentChatConfiguration stopped = new AgentChatConfiguration();
        Field flag = AgentChatConfiguration.class.getDeclaredField("chatAuditEmergencyStop");
        flag.setAccessible(true);
        flag.setBoolean(stopped, true);

        AgentChatOrchestratorPort port = stopped.agentChatOrchestrator(
                mock(AgentChatAuditPort.class),
                mock(RagRetrievalPort.class),
                mock(ChatStreamGateway.class),
                mock(AgentRouter.class),
                mock(AgentQueryPort.class),
                mock(ExecutionSnapshotPort.class),
                mock(ClosureResolver.class),
                mock(CapAgentDefPort.class),
                mock(CapToolDefPort.class),
                mock(ModelResolver.class),
                mock(ToolInvocationGate.class),
                mock(ContextStateVerifier.class),
                mock(ContextPolicyVersionPort.class),
                mock(RequestAssemblyPort.class),
                mock(ContextCheckpointEmitter.class),
                mock(RagHitQuotaPort.class));

        assertThat(port)
                .as("应急熔断必须让编排器「完全不被包装」——否则救火时仍会走审计代码路径")
                .isInstanceOf(AgentChatOrchestrator.class);
    }
}
