package cn.studykid.multiagent.application.cap;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * CAP 授权链路准入开关（P2 · 06 §3.3 灰度迁移）。
 *
 * <p>⚠️ <b>全局默认关闭</b>。是否在生产全局开启，取决于<b>运行时灰度证据</b>，
 * 而非（现已过期的）代码门禁 —— 见下：
 * <ul>
 *   <li><b>P5 代码前置已落地（2026-11-04 核实）</b>：发布版本解析已由过渡实现
 *       （对象级解析 + 回滚守卫）替换为「当前指针 + CAS」的正式读路径 ——
 *       {@code JdbcPublishedVersionPort}（指针→PUBLISHED release→object_versions）
 *       配 {@code JdbcConfigPointerRepository} 的 CAS 原子发布，且已接入
 *       {@code CachingClosureResolver} 准入/闭包解析（详见《P0-1d 全量开启前置评估》）。
 *       「取最新已发布版本行而非指针指向版本、回滚场景会指错对象」的缺口<b>已闭合</b>；</li>
 *   <li><b>剩余前置是运行时灰度验证</b>：灰度阶段 AUTHZ_MISMATCH 连续为 0、
 *       工具可用率无退化（《阶段一》I2-2 验收判据）。这段证据只能靠开关在
 *       低风险灰度租户上打开跑出数据，<b>代码层面无法替代</b>。</li>
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
 *
 * <p>⚠️ <b>M3 / G1 的进展（2026-10-31）</b>：HITL 所需的<b>数据模型与状态机能力已就位</b> ——
 * {@code cap_approval_state} 新增 {@code AWAITING_HUMAN}（待人工决策）、
 * {@code approver_id}/{@code decided_at} 在该状态下可空、
 * {@code ApprovalStateMachine#requestHuman} 可在运行期登记待办、
 * {@code Acquisition#requiresHumanDecision} 能区分「等一等就能过」与「永远过不了」。
 * <b>全局开关仍保持默认 false</b>：CAP 链路的准入判定在代码上已依赖 P5 指针读路径
 * （2026-11-04 已落地），但生产全局开启仍须待运行时灰度证据（I2-2 验收判据），不擅自翻转。
 *
 * <p><b>灰度白名单（阶段一 · P0-1c）</b>：本开关额外支持<b>租户级灰度</b> ——
 * {@code isEnabled(tenantId)} 在「全局开启」<i>或</i>「该租户在白名单」时返回 {@code true}：
 * <ul>
 *   <li>全局开关关闭时，仍可仅对 {@code config.flag.cap-authorization-enforced.tenants}
 *       里列出的少量低风险租户开启 CAP 链路，用于切分观察窗口（阶段 1：单测试租户）；
 *       </li>
 *   <li>全局开关打开后，白名单失效（所有租户都走 CAP 链路）。</li>
 * </ul>
 * <b>白名单是「放行个别租户」的灰度通道，不是「豁免/旁路」</b>：
 * 列进去的租户恰恰是「提前几小时/几天走 CAP 完整链路」的先行者，
 * 而不是「绕开 CAP」的特权户。白名单为空 = 无租户级灰度（与旧行为逐字节一致）。
 */
public final class CapEnforcementSwitch {

    /** 配置键。 */
    public static final String KEY = "config.flag.cap-authorization-enforced";

    private final AtomicBoolean enabled;
    /** 灰度租户白名单（P0-1c）。可为空 = 无租户级灰度，与旧行为逐字节一致。 */
    private final Set<String> tenantAllowlist;

    /** 无灰度白名单（与旧行为一致）。白名单为空 = 仅全局开关生效。 */
    public CapEnforcementSwitch(boolean initiallyEnabled) {
        this(initiallyEnabled, Set.of());
    }

    /**
     * @param initiallyEnabled 全局开关初值
     * @param tenantAllowlist  灰度租户白名单（可为空集合；{@code null} 按空集合处理）
     */
    public CapEnforcementSwitch(boolean initiallyEnabled,
                                @Nullable Set<String> tenantAllowlist) {
        this.enabled = new AtomicBoolean(initiallyEnabled);
        this.tenantAllowlist = tenantAllowlist == null ? Set.of() : Set.copyOf(tenantAllowlist);
    }

    /**
     * @return 当前全局是否启用 CAP 授权链路（不区分租户）。
     *         <b>判定应以 {@link #isEnabled(String)} 为准</b>，本方法只反映全局位。
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 该租户当前是否启用 CAP 授权链路（P0-1c 灰度判定）。
     *
     * <p>生效条件 = 全局开启 <b>或</b> 租户在灰度白名单内。
     * 白名单内的租户是「提前切到 CAP 完整链路」的先行者，不是「绕开 CAP」的特权户。
     *
     * @param tenantId 租户标识；{@code null}/{@code blank} 一律按「不在白名单」处理
     *                 （灰度必须显式列名，不许靠空串/空白打成通配）
     */
    public boolean isEnabled(@Nullable String tenantId) {
        if (enabled.get()) {
            return true;
        }
        // ⚠️ 显式判空：Set.copyOf 的 contains(null) 会抛 NPE，而灰度必须显式列名，
        //    不允许空串/空白打成通配 —— 故 null/blank 一律视为不在白名单。
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return tenantAllowlist.contains(tenantId);
    }

    /**
     * @return 当前灰度白名单（只读视图）。空 = 无租户级灰度。
     */
    public @NonNull Set<String> tenantAllowlist() {
        return tenantAllowlist;
    }

    /** 开启（全局灰度）。 */
    public void enable() {
        enabled.set(true);
    }

    /** 关闭（全局回退；白名单内租户仍走 CAP，见 {@link #isEnabled(String)}）。 */
    public void disable() {
        enabled.set(false);
    }
}
