package cn.studykid.multiagent.application.cap;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;

/**
 * CAP 授权链路准入开关（P2 · 06 §3.3 灰度迁移）。
 *
 * <p>⚠️ <b>默认关闭，且 P5 之前不得在生产开启</b>。
 * 理由（P1 交付说明的交接结论）：
 * <ul>
 *   <li>P1 的发布版本解析是<b>过渡实现</b>（对象级解析 + 回滚守卫），
 *       P5 必须替换为「当前指针 + CAS」的正式读路径；</li>
 *   <li>在指针读路径落地前开启 CAP 授权链路，等于让授权判定依赖一个
 *       <b>已知不完整</b>的发布语义（取「最新已发布版本行」而非「指针指向的版本」）——
 *       回滚场景下两者会指向不同对象。</li>
 * </ul>
 *
 * <p>本开关是<b>迁移闸门</b>，不是安全开关：关闭时走既有（未接入能力链）的链路，
 * 开启时走 §5.3 完整协议。两条链路都<b>只在 {@code ToolInvocationGate} 内部</b>可达，
 * 不存在「第三条路径」。
 *
 * <p>⚠️ <b>开关关闭不放松既有闸门</b>：既有链路的 Schema 校验、执行时重新授权、
 * 账本登记、审批、预算预留一个都不少。开关只决定「是否额外叠加能力链授权与执行准入」。
 *
 * <p>⚠️ <b>回退语义</b>：关闭后立即回到既有链路，<b>不删除</b>已写入的
 * {@code cap_*} 数据（与 {@link CapDualWriteSwitch} 同构）：
 * 关开关 = 停止走新判定 ≠ 删数据。
 */
public final class CapEnforcementSwitch {

    /** 配置键。 */
    public static final String KEY = "config.flag.cap-authorization-enforced";

    private final AtomicBoolean enabled;

    public CapEnforcementSwitch(boolean initiallyEnabled) {
        this.enabled = new AtomicBoolean(initiallyEnabled);
    }

    /**
     * @return 当前是否启用 CAP 授权链路
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /** 开启（灰度）。 */
    public void enable() {
        enabled.set(true);
    }

    /** 关闭（回退）。 */
    public void disable() {
        enabled.set(false);
    }
}
