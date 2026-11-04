package cn.studykid.multiagent.bootstrap.config;

import cn.studykid.multiagent.application.cap.CapEnforcementSwitch;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * CAP 授权链路状态指标（阶段一 · I2 · P0-1c 监控面板）。
 *
 * <p>暴露 {@code cap.enforcement_status} Gauge（阶段一设计 §2.2）：
 * <ul>
 *   <li>{@code 0} —— 全局关闭且无灰度白名单（全租户走既有链路）；</li>
 *   <li>{@code 1} —— 全局关闭但有灰度白名单（部分租户切入 CAP 完整链路）；</li>
 *   <li>{@code 2} —— 全局开启（所有租户走 CAP 完整链路）。</li>
 * </ul>
 *
 * <p><b>为什么不放在 {@code CapEnforcementSwitch} 里</b>：{@code CapEnforcementSwitch} 属于
 * {@code agent-application} 模块，该模块<b>不依赖 Micrometer</b>；把指标出口放在
 * {@code platform-bootstrap}（同时可见开关与 {@code MeterRegistry}），保持模块边界干净。
 *
 * <p><b>采样语义</b>：{@code Gauge} 是拉模型 —— Prometheus 每次抓取时调用 {@link #statusValue()}，
 * 实时反映开关的当前状态，无需在 {@code enable()/disable()} 时主动翻转。
 */
public final class CapEnforcementMetrics {

    static final String ENFORCEMENT_STATUS = "cap.enforcement_status";

    private final CapEnforcementSwitch capSwitch;

    public CapEnforcementMetrics(CapEnforcementSwitch capSwitch, MeterRegistry registry) {
        this.capSwitch = capSwitch;
        Gauge.builder(ENFORCEMENT_STATUS, this::statusValue)
                .description("CAP authorization enforcement status (0=off, 1=gray, 2=on)")
                .register(registry);
    }

    /** 当前状态值：0=全局关且无灰度；1=全局关但有灰度白名单；2=全局开。 */
    double statusValue() {
        return statusOf(capSwitch);
    }

    /** 纯函数：便于单测钉死三种状态映射。 */
    public static double statusOf(CapEnforcementSwitch capSwitch) {
        if (capSwitch.isEnabled()) {
            return 2d;
        }
        return capSwitch.tenantAllowlist().isEmpty() ? 0d : 1d;
    }
}