package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.api.governance.ApprovalController;
import cn.studykid.multiagent.api.governance.AuditQueryController;
import cn.studykid.multiagent.api.governance.RunListController;
import cn.studykid.multiagent.api.governance.UnknownOperationController;
import cn.studykid.multiagent.infrastructure.persistence.JdbcApprovalQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcAuditQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcRunListQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.JdbcUnknownOperationRepository;
import cn.studykid.multiagent.ports.persistence.ApprovalQueryPort;
import cn.studykid.multiagent.ports.persistence.AuditQueryPort;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;
import cn.studykid.multiagent.ports.persistence.RunListQueryPort;
import cn.studykid.multiagent.ports.persistence.UnknownOperationPort;

/**
 * 治理端点装配连通性测试（P4-D13 · ⛔ 反 R-1）。
 *
 * <p><b>⛔ 本测试存在的唯一理由</b>：P4-D08 的 R-1 教训——
 * {@code GetStudentProfileTool} 等三个类写完了、测试也全绿，
 * 但<b>从未注册为 Bean</b>，于是 Worker 注入的 {@code List<ToolHandler>} 生产恒空，
 * 工具**从不执行**，而「系统表面完全正常」。
 *
 * <p>本片新增 4 个端口 + 4 个控制器共 8 个类。
 * 若其中任何一个没被扫到或没被装配，<b>端点会在启动时就报缺 Bean 而失败</b>
 * （这是构造器注入的天然保护）；但反过来——
 * 若某个端口被注册成了一个<b>替身</b>（如 {@code NO_AUDIT} 那种空实现），
 * 启动仍然全绿，只有真的调接口才会发现。
 *
 * <p>故本测试<b>显式断言「端口注入的是 JDBC 实现」</b>，
 * 把「装配对了」这件事从「启动没报错」升级为「可断言的事实」。
 */
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "agent.worker.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P4-D13 治理端点装配（G7~G10）")
class GovernanceWiringTest {

    @Autowired
    private RunListQueryPort runListQueryPort;

    @Autowired
    private ApprovalQueryPort approvalQueryPort;

    @Autowired
    private UnknownOperationPort unknownOperationPort;

    @Autowired
    private AuditQueryPort auditQueryPort;

    @Autowired
    private RunListController runListController;

    @Autowired
    private ApprovalController approvalController;

    @Autowired
    private UnknownOperationController unknownOperationController;

    @Autowired
    private AuditQueryController auditQueryController;

    @Autowired
    private RateLimitPort rateLimitPort;

    @Test
    @DisplayName("⛔ 四个端口都注入 JDBC 实现（而非替身）")
    void portsResolveToJdbcImplementations() {
        assertThat(runListQueryPort)
                .as("⛔ Run 列表端点若拿到替身，检索台会永远返回空列表而无人察觉")
                .isInstanceOf(JdbcRunListQueryRepository.class);
        assertThat(approvalQueryPort)
                .as("⛔ 审批端口若拿到替身，审批台会永远空且决定永远「成功」")
                .isInstanceOf(JdbcApprovalQueryRepository.class);
        assertThat(unknownOperationPort)
                .as("⛔ UNKNOWN 端口若拿到替身，处置台会显示空且处置静默失败")
                .isInstanceOf(JdbcUnknownOperationRepository.class);
        assertThat(auditQueryPort)
                .as("⛔ 审计端口若拿到替身，审计页会永远空——而「审计为空」看起来像合规")
                .isInstanceOf(JdbcAuditQueryRepository.class);
    }

    @Test
    @DisplayName("✅ 四个控制器都已装配")
    void controllersAreRegistered() {
        assertThat(runListController).isNotNull();
        assertThat(approvalController).isNotNull();
        assertThat(unknownOperationController).isNotNull();
        assertThat(auditQueryController).isNotNull();
        assertThat(rateLimitPort).isNotNull();
    }
}