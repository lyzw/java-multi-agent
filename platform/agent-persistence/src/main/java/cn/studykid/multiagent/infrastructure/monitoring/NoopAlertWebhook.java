package cn.studykid.multiagent.infrastructure.monitoring;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.monitoring.AlertWebhookPort;

/**
 * 空实现（告警外发<b>默认关闭</b>时的落点）。
 *
 * <p>⚠️ <b>为什么用空实现而不是 {@code null}</b>：
 * 调用方（审计写入路径）不必判空 —— 「通道关闭」与「没有通道」在
 * 类型上就分开，比运行时 if 更不容易在新增调用点时漏判。
 */
public final class NoopAlertWebhook implements AlertWebhookPort {

    @Override
    public void onCriticalDeny(@NonNull CriticalDenyAlert alert) {
        // 默认关闭：不外发、不留痕（审计本身已完整记录）。
    }
}
