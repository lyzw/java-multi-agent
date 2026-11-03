package cn.studykid.multiagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import cn.studykid.multiagent.testsupport.db.ItTargetDatabase;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IT 目标库隔离守卫（bootstrap 模块）。
 *
 * <p>本模块的端点契约 IT 是「每轮往种子租户 {@code t-a} 写 Run / agent 行」的主要来源
 * （{@code RunCreationEndpointIT}、{@code AgentEndpointIT} 等，见
 * {@code SeedTenantWriteArchitectureTest.REVIEWED_WRITERS} 的台账）。
 * 这些残留之所以<b>可以接受</b>，前提是<b>跑在隔离库上</b> —— 一旦
 * {@code IT_PG_URL} 指回应用库，它们就变成对运行实例的污染：常驻 worker 会领走
 * {@code QUEUED} Run 并改写终态，残留还会挤占 {@code claimTasks} 的 {@code LIMIT} 配额。
 *
 * <p>判据与反证见 {@link ItTargetDatabase} 与 {@code ItTargetDatabaseTest}；
 * 与 {@code agent-infrastructure} 的同名守卫共用同一份判据，只是各自在模块的
 * surefire 阶段先炸，避免「跑完 50 个 IT 才看到一句人话」。
 */
class ItTargetDatabaseIsolationTest {

    @Test
    @DisplayName("⚠️⚠️ 端点契约 IT 的租户取自 DB，目标库必须是隔离库而非应用库")
    void targetIsNotApplicationDatabase() {
        String itUrl = System.getenv(ItTargetDatabase.IT_URL_ENV);
        Optional<String> appUrl = ItTargetDatabase.applicationDatasourceUrl();

        assertThat(ItTargetDatabase.violation(itUrl, appUrl.orElse("")))
                .as("""
                        ⚠️⚠️ IT 连接串指向了应用库：本模块的端点契约测试会往 t-a 写 Run/agent 行，
                        常驻实例随即领走 QUEUED Run、改写终态，断言变成竞态；
                        execution_snapshot 受不可变触发器保护，污染不可逆。
                        改用隔离库：source scripts/it-env.sh（见 docs/集成测试（IT）运行指南.md）""")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 判据不是空转：platform.env 声明的应用库若当目标，必须被判红")
    void judgementCoversDeclaredApplicationDatabase() {
        Optional<String> appUrl = ItTargetDatabase.applicationDatasourceUrl();
        if (appUrl.isEmpty()) {
            return; // platform.env 被 .gitignore 忽略，CI 上无此文件，仅剩硬编码名单兜底
        }

        assertThat(ItTargetDatabase.violation(appUrl.get(), appUrl.get()))
                .as("把真实应用库当作 IT 目标必须被拒绝")
                .isPresent();
    }
}
