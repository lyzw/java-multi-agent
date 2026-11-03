package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import cn.studykid.multiagent.infrastructure.security.AuthzDecisionRecorder;
import cn.studykid.multiagent.infrastructure.security.JdbcAuthzDecisionLog;

/**
 * 授权决策日志<b>就绪守卫</b>（D6 / P2）。
 *
 * <p>⚠️️ <b>本类存在的唯一理由</b>：{@code agent.authz.decision-log.enabled=true} 只是「想记」，
 * 不代表「记下来了」。这条链路上有三处会静默失效：
 * <ol>
 *   <li><b>容器里没有 AccessGate</b>（例如影子装配整体关闭）→ 开关开着，却没有任何判定会走到记录器
 *       —— 「审计已开启」在报表上成立，实际一行不写；</li>
 *   <li><b>表没建 / 没授权</b> → 每次判定写失败，而失败被 {@code JdbcAuthzDecisionLog}
 *       按 60s 窗口限频成一条 WARN（这是刻意的：审计故障不该拖垮请求），
 *       于是<b>静默无审计</b>；</li>
 *   <li><b>表结构漂移</b>（列名改了一处）→ {@code has_table_privilege} 仍为 true，
 *       权限检查看起来完全正常，写入却持续失败。</li>
 * </ol>
 *
 * <p>第 2、3 条只能靠<b>真的走一遍 INSERT</b> 发现，故本守卫调用
 * {@link JdbcAuthzDecisionLog#probe()}（{@code INSERT … SELECT … WHERE FALSE}，零行写入）。
 * 这是本项目反复强调的那条纪律：<b>「配了」与「生效了」之间必须有一次实测</b>。
 *
 * <p><b>fail-closed</b>：开关开启而上述任一条件不满足 → <b>拒绝启动</b>。
 * 理由：审计是合规交付物，宁可起不来，也不要「以为在记、其实没记」地跑上两周。
 * 开关关闭时本类只打一行 INFO —— 不制造噪音，也不假装已验证。
 */
public class AuthzDecisionLogGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthzDecisionLogGuard.class);

    private final boolean enabled;
    private final ObjectProvider<AuthzDecisionRecorder> recorders;

    public AuthzDecisionLogGuard(boolean enabled,
                                 @NonNull ObjectProvider<AuthzDecisionRecorder> recorders) {
        this.enabled = enabled;
        this.recorders = recorders;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        if (!enabled) {
            log.info("授权决策日志未开启（agent.authz.decision-log.enabled=false）："
                    + "判定不落库，观测口径仅 mismatch/taint 指标 + 日志");
            return;
        }

        AuthzDecisionRecorder recorder = recorders.getIfAvailable();
        if (recorder == null) {
            // ⚠️ 最常见的成因不是「忘了配」，而是「影子装配没开」——
            //    记录器 Bean 随影子装配一起存在，二者缺失是同一个事实。
            throw new IllegalStateException(
                    "决策日志已开启，但容器中没有任何 AuthzDecisionRecorder Bean —— "
                            + "说明产生 AccessGate 的装配（当前为 agent.authz.shadow.enabled）没有生效。"
                            + "此配置下每一次判定都不会被记录，属「开关开着却零记录」的假开启。"
                            + "请同时开启 agent.authz.shadow.enabled，或关闭 decision-log。");
        }

        if (!(recorder instanceof JdbcAuthzDecisionLog jdbc)) {
            // 装配被替换成了别的实现（例如测试替身误入生产配置）——同样不许静默通过
            throw new IllegalStateException(
                    "决策日志已开启，但当前记录器实现为 " + recorder.getClass().getName()
                            + "，不是 JdbcAuthzDecisionLog —— 判定不会写入 platform.authz_decision_log。"
                            + "请检查 AuthzShadowConfiguration#authzDecisionRecorder 是否被覆盖。");
        }

        if (!jdbc.probe()) {
            throw new IllegalStateException(
                    "决策日志已开启，但零行写入探测失败（详见上一条 ERROR 日志）："
                            + "platform.authz_decision_log 缺失 / 当前角色无 INSERT / 列已漂移。"
                            + "请执行 deploy/sql/platform/01_platform_ddl.sql 中该表的定义，"
                            + "并重跑 deploy/sql/platform/03_platform_roles.sql 的授权段落"
                            + "（应用角色应为「只写不读」）。");
        }

        log.info("✅ 授权决策日志就绪：platform.authz_decision_log 可写（零行探测通过）");
    }
}
