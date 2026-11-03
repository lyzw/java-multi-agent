package cn.studykid.multiagent.infrastructure.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.studykid.multiagent.testsupport.db.ItTargetDatabase;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IT 目标库隔离守卫：<b>本模块的集成测试不得跑在应用库上</b>。
 *
 * <p>⚠️ <b>为什么需要它</b>（而不是只在 {@code scripts/it-env.sh} 里拦）：
 * 那个脚本只是一条入口。IDE 里单跑一个 IT、CI 直接 {@code mvn verify}、
 * 谁手写 {@code export IT_PG_URL=…/agent_dev}，全都绕得开。判据必须跟着构建走。
 * 共库的代价是<b>污染不可逆</b>：{@code execution_snapshot} 有
 * {@code forbid_snapshot_mutation} 触发器，多出来的行物理删不掉；而失败现场会指向
 * 完全无关的地方（僵尸 {@code RUNNING} 挤占 {@code claimTasks} 的 {@code LIMIT}，
 * 后来者报「任务未被领取」，看起来像 claim 逻辑坏了）。
 *
 * <p>⚠️ <b>为什么放在 surefire 阶段（{@code *Test} 而不是 {@code *IT}）</b>：
 * surefire 在 failsafe 之前执行。让它在<b>任何 IT 连库之前</b>失败，得到的是
 * 「一条可读的错」，而不是「五十条各自诡异的断言失败」。
 *
 * <p>判据实现见 {@link ItTargetDatabase}；它的反证（该红时必须红）在
 * {@code agent-test-support} 的 {@code ItTargetDatabaseTest} 里。
 */
class ItTargetDatabaseIsolationTest {

    @Test
    @DisplayName("⚠️⚠️ IT 目标库不得是应用库（否则常驻实例会抢 IT 造的数据）")
    void targetIsNotApplicationDatabase() {
        String itUrl = System.getenv(ItTargetDatabase.IT_URL_ENV);
        Optional<String> appUrl = ItTargetDatabase.applicationDatasourceUrl();

        assertThat(ItTargetDatabase.violation(itUrl, appUrl.orElse("")))
                .as("""
                        ⚠️⚠️ IT 连接串指向了应用库。
                        共用应用库时：常驻实例会领走 IT 造的 QUEUED Run 并改写终态（断言变竞态），
                        历史残留还会挤占 claimTasks 的 LIMIT 配额（报「任务未被领取」，指向错误方向）；
                        execution_snapshot 受不可变触发器保护、物理删不掉，污染不可逆。
                        改用隔离库：source scripts/it-env.sh（详见 docs/集成测试（IT）运行指南.md）""")
                .isEmpty();
    }

    @Test
    @DisplayName("⚠️ 判据不是空转：platform.env 声明的应用库若当目标，必须被判红")
    void judgementCoversDeclaredApplicationDatabase() {
        Optional<String> appUrl = ItTargetDatabase.applicationDatasourceUrl();
        // platform.env 被 .gitignore 忽略（CI 上没有此文件）→ 交叉比对无从下手，
        // 此时只保留 ItTargetDatabase.APPLICATION_DATABASES 硬编码兜底。
        if (appUrl.isEmpty()) {
            return;
        }

        assertThat(ItTargetDatabase.violation(appUrl.get(), appUrl.get()))
                .as("把 platform.env 里声明的应用库当作 IT 目标，判据必须拒绝（否则守卫是装饰）")
                .isPresent();
    }

    @Test
    @DisplayName("⚠️ user.dir 上溯必须能定位仓库根（否则交叉比对在 IDE/CI 下静默失效）")
    void envFileLookupIsNotSilentlyBroken() {
        Path moduleDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        assertThat(moduleDir)
                .as("surefire 的 user.dir 应是存在的模块目录，上溯逻辑才有起点")
                .exists();

        ItTargetDatabase.applicationEnvFile()
                .ifPresent(env -> assertThat(env).as("定位到的 platform.env 必须可读").isReadable());
    }

    @Test
    @DisplayName("✅ 合规目标不抛异常（隔离库的常规路径不得被守卫打断）")
    void isolatedTargetDoesNotThrow() {
        assertThatCode(() -> ItTargetDatabase.requireIsolatedFromEnvironment())
                .as("当前环境若是隔离库，这里必须静默")
                .doesNotThrowAnyException();
    }
}
