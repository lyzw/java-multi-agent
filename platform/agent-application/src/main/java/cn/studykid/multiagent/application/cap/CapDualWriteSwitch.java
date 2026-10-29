package cn.studykid.multiagent.application.cap;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.NonNull;

/**
 * 迁移双写开关（P0-T6 · 06 §3.3）。
 *
 * <p><b>出厂默认开启</b>（{@code config.flag.cap-dual-write}，装配见
 * {@code ApplicationConfiguration#capDualWriteSwitch}）。
 * 该开关原本是 P0 的灰度闸门 —— 双写会带来写放大与延迟，未经验证的迁移
 * 不该直接作用于生产写路径，故当时默认 false。
 * 但 {@code execution_capability_snapshot} 后来成了<b>执行准入的前置条件</b>
 * （{@code RootAgentModelConfigProvider} 靠它确定根 Agent 的 release），
 * 于是「默认关」的含义从「少写一张表」变成「<b>按默认配置启动的进程跑不了任何 Run</b>」。
 *
 * <p><b>回退语义</b>（P0 退出门禁：「开关切 false 后旧路径完全接管，无数据丢失」）：
 * 关闭后所有双写动作<b>立即停止</b>，读路径仍以旧模型
 * （{@code agent_version} / {@code execution_snapshot}）为准 ——
 * 这就是「旧路径完全接管」。由于双写是<b>追加</b>而非<b>搬移</b>，
 * 关掉开关不会删除任何已写数据，故「无数据丢失」成立。
 * ⚠️ 代价要写清：回退的同时<b>执行准入也一起关闭</b>，
 * 补偿控制是启动期的一条 WARN（见 {@code CapDualWriteService} 构造器）。
 *
 * <p>⚠️ 开关只控制<b>写入</b>，<b>不删除</b>已写入 {@code cap_*} 的行。
 * 清理是 P5 切换读路径后的独立动作（06 §3.3 第 7 步「停止旧模型写入，再归档/移除旧字段」），
 * 与本开关分离，避免「关开关 = 删数据」这种危险耦合。
 */
public final class CapDualWriteSwitch {

    /** 配置键（与 P0 计划文档一致）。 */
    public static final String KEY = "config.flag.cap-dual-write";

    private final AtomicBoolean enabled;

    public CapDualWriteSwitch(boolean initiallyEnabled) {
        this.enabled = new AtomicBoolean(initiallyEnabled);
    }

    /**
     * @return 当前是否开启双写
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 开启双写（灰度）。
     */
    public void enable() {
        enabled.set(true);
    }

    /**
     * 关闭双写（回退）。
     *
     * <p>⚠️ <b>立即生效</b>：后续的双写调用直接跳过，
     * 旧读/写路径完全接管。
     */
    public void disable() {
        enabled.set(false);
    }
}
