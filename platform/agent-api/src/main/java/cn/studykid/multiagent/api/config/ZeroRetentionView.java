package cn.studykid.multiagent.api.config;

import java.util.List;
import java.util.Map;

/**
 * 零保留（DEC-10 GATE-2）运行态视图。
 *
 * <p><b>⚠️ 本视图刻意区分「声明」与「运行时确认」</b>：
 * yml 只声明了供应商清单（{@code confirmed-providers}），但<b>不含证据出处</b>，
 * 本片不伪造证据，故注册表里这些供应商均为 {@code PENDING}
 * （{@code pending} ≠ {@code confirmed}）→ 闸门仍 fail-closed 拒绝真实学生数据。
 *
 * <p>前端据此显示：「已声明 N 家，当前均无有效确认证据 → 仍 fail-closed 拒绝」，
 * 而非误导性的「已确认放行」。
 */
public record ZeroRetentionView(
        /** yml 声明的供应商清单（来源 of truth：配置声明）。 */
        List<String> declaredProviders,
        /** 注册表实际状态：providerId → 有效状态（PENDING/CONFIRMED/REJECTED/EXPIRED）。 */
        Map<String, String> registryStatus,
        /** 是否至少有一家已真正确认（CONFIRMED）。本片恒为 false。 */
        boolean anyConfirmed,
        /** GATE-1 是否仍在生效（无任何供应商允许真实数据接入）。本片恒为 true。 */
        boolean failClosed) {
}
