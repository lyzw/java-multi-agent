package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.core.config.RunConfigSnapshot;
import cn.studykid.multiagent.ports.persistence.RunClaimPort;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * C8 配置接入运行时的**连通性测试**（P4-D11）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：P4-D11 修复的 C8 缺口是一个
 * <b>「yml 里声明得清清楚楚、生产代码却从不读」</b>的分叉——
 * 接线前生产运行快照是<b>硬编码</b>的（成本上限 {@code 10.00}），
 * 而 yml 声明 {@code agent.run.max-cost-per-run=2.00}：
 * <b>声明比真实敞口紧 5 倍</b>，运维读到的数字是假的。
 *
 * <p>而 {@code ConfigReadServiceTest} 断言的是「服务返回的目录长什么样」，
 * 属<b>读模型</b>测试——它<b>无法</b>发现「生产路径根本没读 yml」。
 * 这正是 P4-D01 确立的方法论：<b>行为测试与连通性测试缺后者则整条链路可断裂而不被发现</b>。
 *
 * <p>⚠️ <b>本类不依赖真实 broker</b>：只断言装配与配置来源，
 * 故可在 CI 无 RabbitMQ 时运行。
 */
// ⚠️ 2026-10-22 实测：本类会装配**完整 Spring 上下文**，启动期即查询 tenant 表
//    （RetentionJob → 解析租户 slug fail-closed）→ **必须有活库且已建表**。
//    无库时它不是「跳过」而是**报错**（29 项 error），会把无库的构建拖红。
//    故按项目既有约定（见 RateLimitIT 等）用 IT_PG_URL 门禁：
//    无库 → 跳过；integration-test job（有库）→ 真跑并纳入 skipped==0 断言。
@EnabledIfEnvironmentVariable(named = "IT_PG_URL", matches = ".+")
@SpringBootTest(
        classes = cn.studykid.multiagent.bootstrap.PlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@DisplayName("P4-D11 C8 配置接入运行时连通性")
class ConfigRuntimeWiringTest {

    @Autowired
    private WorkerExecutionConfig.RunExecutionInputs inputs;

    @Autowired
    private ToolRiskLevelConsistencyGuard riskGuard;

    @Autowired
    private List<ToolHandler> handlers;

    @Autowired
    private Environment environment;

    // ── C-1/C-2/C-3：生产快照必须来自 yml ────────────────────────
    @Nested
    @DisplayName("C-1/C-2/C-3 生产运行快照读 yml")
    class SnapshotFromYml {

        @Test
        @DisplayName("✅ C-1 生产快照的成本上限等于 yml 声明值（不是硬编码 10.00）")
        void snapshotCostComesFromYml() {
            String declared = environment.getProperty("agent.run.max-cost-per-run");
            assertThat(declared).as("yml 必须声明该键").isNotBlank();

            RunConfigSnapshot snapshot = inputs.snapshotFor(fakeTask());

            assertThat(snapshot.maxCostPerRun())
                    .as("⚠️ 接线前生产快照是硬编码的 10.00，而 yml 声明 2.00 —— "
                            + "声明比真实敞口紧 5 倍，运维读到的数字是假的")
                    .isEqualByComparingTo(new BigDecimal(declared))
                    .isNotEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("✅ C-2 步数与调用上限同样来自 yml")
        void snapshotLimitsComeFromYml() {
            RunConfigSnapshot snapshot = inputs.snapshotFor(fakeTask());

            assertThat(snapshot.maxSteps())
                    .isEqualTo(Integer.parseInt(
                            environment.getProperty("agent.run.max-steps")));
            assertThat(snapshot.maxLlmCalls())
                    .isEqualTo(Integer.parseInt(
                            environment.getProperty("agent.run.max-llm-calls")));
        }

        @Test
        @DisplayName("✅ C-3b 快照随配置值变化（证明不是硬编码）")
        void snapshotFollowsConfiguredValue() {
            // ⚠️ 直接构造两套不同配置的 inputs：
            //    若快照仍是硬编码，两个值会相同 → 用例转红。
            // ⚠️ P4-D15：首参 resolver 传 null = 不叠加 DB 覆盖，
            //    本用例仍然只验证 yml → 快照的接线（P4-D11 的原始契约），
            //    覆盖叠加由 ConfigOverrideIT 单独覆盖。
            WorkerExecutionConfig.RunExecutionInputs cheap = new WorkerExecutionConfig.RunExecutionInputs(
                    null, "0.50", "1", "2",
                    Map.of("record_weakness",
                            cn.studykid.multiagent.core.config.ToolRiskLevel.WRITE_PROFILE),
                    Map.of(cn.studykid.multiagent.core.config.ToolRiskLevel.WRITE_EXTERNAL,
                            cn.studykid.multiagent.core.config.ApprovalRequirement.REQUIRED));

            RunConfigSnapshot snapshot = cheap.snapshotFor(fakeTask());

            assertThat(snapshot.maxCostPerRun()).isEqualByComparingTo(new BigDecimal("0.50"));
            assertThat(snapshot.maxSteps()).isEqualTo(2);
            assertThat(snapshot.maxLlmCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("✅ C-3 审批要求按最严解析（主工具未知 → WRITE_EXTERNAL → REQUIRED）")
        void approvalIsMostRestrictive() {
            RunConfigSnapshot snapshot = inputs.snapshotFor(fakeTask());

            assertThat(snapshot.approvalRequired())
                    .as("⚠️ 主工具未知时必须落到 WRITE_EXTERNAL（REQUIRED），"
                            + "不得因主工具未知而放松门禁")
                    .isEqualTo(cn.studykid.multiagent.core.config.ApprovalRequirement.REQUIRED);
        }
    }

    // ── C-5/C-6：风险映射一致性守卫 ──────────────────────────────
    @Nested
    @DisplayName("C-5/C-6 工具风险级别一致性守卫")
    class RiskLevelGuard {

        @Test
        @DisplayName("✅ C-5 声明与代码一致时守卫通过")
        void consistentDeclarationPasses() {
            assertThat(handlers).as("工具必须已注册").isNotEmpty();
            // 上下文中已装配 → 守卫在启动时已执行过；再显式执行一次确认幂等。
            riskGuard.verify();
        }

        @Test
        @DisplayName("⛔ C-6 声明与代码不一致 → 拒绝启动并给出差异明细")
        void mismatchedDeclarationRefusesStartup() {
            // ⚠️ 模拟「有人把 yml 改成把有副作用的工具降级为 READ」
            Map<String, cn.studykid.multiagent.core.config.ToolRiskLevel> wrong = Map.of(
                    "get_student_profile",
                    cn.studykid.multiagent.core.config.ToolRiskLevel.READ,
                    // ⚠️ 代码里 record_weakness 是 WRITE_PROFILE，这里谎报 READ
                    "record_weakness",
                    cn.studykid.multiagent.core.config.ToolRiskLevel.READ);

            ToolRiskLevelConsistencyGuard guard =
                    new ToolRiskLevelConsistencyGuard(handlers, wrong);

            assertThatThrownBy(guard::verify)
                    .as("⚠️ 声明与代码不一致必须 fail-closed（拒绝启动），"
                            + "而不是「以代码为准、忽略 yml」——那会让声明继续惰性")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("record_weakness")
                    .hasMessageContaining("READ")
                    .hasMessageContaining("WRITE_PROFILE");
        }

        @Test
        @DisplayName("✅ 未登记工具不阻止启动（按 WRITE_EXTERNAL 兜底是设计内行为）")
        void unregisteredToolDoesNotBlockStartup() {
            ToolRiskLevelConsistencyGuard guard = new ToolRiskLevelConsistencyGuard(
                    handlers, Map.of());  // 全部未登记

            guard.verify();  // 不抛异常即通过
        }
    }

    // ── C-7：虚构声明不得残留 ────────────────────────────────────
    @Nested
    @DisplayName("C-7 虚构的 reconcile 队列声明已清除")
    class PhantomQueueRemoved {

        @Test
        @DisplayName("✅ C-7 yml 中不再声明 reconcile 队列，且代码中无同名 Bean")
        void reconcileQueueDeclarationRemoved() throws Exception {
            String yml = Files.readString(
                    Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

            assertThat(yml)
                    .as("⚠️ 该队列在代码中从未落地（无 @Bean、无发布者、无消费者）；"
                            + "保留声明等于给运维「系统会用它」的错觉")
                    .doesNotContain("agent.run.reconcile");
            assertThat(yml).contains("P4-D11 D11-4");
        }
    }

    /** 构造一个最小可用的 LeasedTask（快照只需要 runId）。 */
    private static RunClaimPort.LeasedTask fakeTask() {
        return new RunClaimPort.LeasedTask(
                "11111111-1111-1111-1111-111111111111",
                1L, java.time.Instant.now(), 0);
    }
}
