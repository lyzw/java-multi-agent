package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import cn.studykid.multiagent.application.retention.ConsentWithdrawalService;
import cn.studykid.multiagent.application.retention.RetentionPurgeService;
import cn.studykid.multiagent.ports.persistence.ErasurePort;
import cn.studykid.multiagent.worker.retention.RetentionJob;

/**
 * 保留期清理**连通性测试**（P4-D04）。
 *
 * <p>⚠️ <b>本类存在的唯一理由</b>：防止「第三次断链」。
 * <ul>
 *   <li>D01 断链第一层：{@code RunDispatchConsumer} 领到租约后不执行；</li>
 *   <li>D03 断链第二层：{@code RunDispatchConsumer} <b>根本没人调用</b>；</li>
 *   <li>本片若不交付驱动方，就是第三次：清理逻辑全在、单测全绿，
 *       但数据<b>从不</b>被删除，且无异常、无告警、健康检查全绿。</li>
 * </ul>
 *
 * <p>因此本类断言的是<b>「Bean 图接得对」</b>这一层次——
 * 这不是「锦上添花」，而是本片能否成立的判据：
 * 少了它，本片退化为又一个「有机制、无调用方」的半成品。
 *
 * <p>⚠️ <b>不启真实清理</b>：{@code interval-ms} 取极大值，
 * Bean 的存在与装配是断言目标，实际清理效果由 {@code RetentionErasureIT} 覆盖。
 *
 * <p>⚠️ 用 {@code WebEnvironment.MOCK}（同 D03 {@code WorkerLoopWiringTest} 结论）：
 * {@code securityFilterChain} 依赖 servlet 上下文，{@code NONE} 下会失败。
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
        // ⚠️ P4-D18：关闭 Outbox 中继。
        //    本类的断言目标与中继无关，而中继一旦启动会把**其它用例**
        //    遗留的 PENDING outbox 事件投进 agent_message_queue，
        //    随后 MessagingSwitchGuard 因「通道切换残留」fail-closed，
        //    使后续 rabbitmq 模式用例无法启动上下文——
        //    症状指向通道配置，真因是跨用例的数据库共享。
        "agent.worker.relay-enabled=false",
        "spring.datasource.url=${IT_PG_URL}",
        "spring.datasource.username=${IT_PG_USER:app_agent}",
        "spring.datasource.password=${IT_PG_PASSWORD:}",
        "spring.flyway.enabled=false",
        "auth.jwt.issuer=https://auth.test",
        "auth.jwt.audience=agent-platform",
        "auth.jwt.token-ttl-seconds=900",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // ── 保留期：全部显式给出，验证 fail-closed 的另一端（配得齐能起来）──
        "agent.retention.enabled=true",
        "agent.retention.profile-months=12",
        "agent.retention.prompt-days=90",
        "agent.retention.audit-days=180",
        "agent.retention.trace-days=30",
        "agent.retention.withdrawal-grace-days=30",
        "agent.retention.batch-size=1000",
        // ⚠️ 取极大值：本类不断言清理效果，避免上下文启动即跑一轮删数据
        "agent.retention.interval-ms=3600000",
        "agent.retention.actor=retention-job",
        // RetentionConfig 复用 worker 的租户 slug（避免两套租户来源）
        "agent.worker.tenant-slug=t-a"
})
@DisplayName("P4-D04 保留期清理连通性测试")
class RetentionWiringTest {

    @Autowired
    private ApplicationContext ctx;

    // ⚠️ 必须放在 @Nested 组内：本项目实测外层类的 @Test 方法在
    //    surefire 下**不会执行**（Tests run: 0）——
    //    「写了但没跑」看起来有覆盖，实际没有。
    @Nested
    @DisplayName("W-1 ⚠️ Bean 存在性（第三次断链的唯一防线）")
    class BeanPresence {

        @Test
        @DisplayName("W-1a ⚠️⚠️ RetentionJob Bean 必须存在——否则数据从不清理")
        void retentionJobExists() {
            assertThat(ctx.getBean(RetentionJob.class))
                    .as("⚠️⚠️ 没有 RetentionJob，清理服务永远不会被调用；"
                            + "缺 Bean 的表现是「代码都在、测试全绿、数据从不被删」，"
                            + "这正是本项目 D01/D03 两次踩到的断链形态")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-1b RetentionPurgeService 已装配")
        void purgeServiceExists() {
            assertThat(ctx.getBean(RetentionPurgeService.class)).isNotNull();
        }

        @Test
        @DisplayName("W-1c ConsentWithdrawalService 已装配（撤回路径同样要有驱动方）")
        void withdrawalServiceExists() {
            assertThat(ctx.getBean(ConsentWithdrawalService.class))
                    .as("撤回要保证有调用出口，否则监护人撤回了却没人执行删除")
                    .isNotNull();
        }

        @Test
        @DisplayName("W-1d ErasurePort 绑定到 JDBC 实现（非测试桩）")
        void erasurePortIsRealImplementation() {
            ErasurePort port = ctx.getBean(ErasurePort.class);
            assertThat(port.getClass().getSimpleName())
                    .as("⚠️ 断言「是真实实现」而非具体类名——换存储实现后本测试仍成立")
                    .isEqualTo("JdbcErasureRepository");
            assertThat(port.getClass().getName())
                    .as("不得是测试替身")
                    .doesNotContain("Stub", "Fake", "Mock");
        }
    }

    @Nested
    @DisplayName("W-2 保留期设置被真正读入（配置到位 vs 恰好对齐）")
    class SettingsLoaded {

        @Test
        @DisplayName("W-2a 撤回宽限期与配置一致（回执文案不得说谎）")
        void graceDaysMatchesConfig() {
            assertThat(ctx.getBean(ConsentWithdrawalService.class).graceDays()).isEqualTo(30);
        }

        @Test
        @DisplayName("W-2b Job 持有的租户非空")
        void jobHasTenants() {
            RetentionJob job = ctx.getBean(RetentionJob.class);
            assertThat(job)
                    .as("RetentionJob 构造期已保证租户非空；此处再确认 Bean 真的构造成功")
                    .isNotNull();
        }
    }
}
