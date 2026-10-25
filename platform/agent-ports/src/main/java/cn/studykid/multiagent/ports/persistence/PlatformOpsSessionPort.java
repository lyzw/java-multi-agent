package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;

/**
 * 平台运维会话（PLATFORM 作用域专用 · 第三十四批）。
 *
 * <p>⚠️ <b>这个端口存在的唯一理由</b>：平台运维角色的数据库连接带
 * {@code BYPASSRLS}，<b>不受任何 RLS 策略约束</b>。
 * 若把它接到应用侧那条「按 scope 自动选池」的通路上，
 * <b>调用方只要把 scope 写成 PLATFORM 就拿到了运维凭据</b> ——
 * DB 层的越权防线被降级成应用层的一行判断。
 *
 * <p>⚠️ <b>会话是运维凭据的「时间窗」</b>：
 * 运维连接只在会话窗口内可交出，窗口外取用一律拒绝。
 * 故平台的每一次平台级写操作都必须<b>整段</b>跑在会话里 ——
 * 包括「申请单落库」「发布事务」「CAS 失败后的基线重读」这类
 * 会**跨越事务边界**的读：它们若落在会话外，
 * 就会拿到一条不受守卫约束的运维连接。
 *
 * <p>⚠️ <b>会话不得包住任何业务读路径</b>：
 * 运维连接不受 RLS 约束，包住租户读路径等于租户隔离整体失效。
 *
 * <p>⚠️ <b>fail-closed</b>：运维角色未接入时，平台作用域操作<b>明确失败</b>，
 * <b>绝不</b>回落到应用角色 —— 回落后 RLS 会拒写，
 * 报错点落在 RLS 策略上，离「这个环境没配运维凭据」这一真因极远。
 */
public interface PlatformOpsSessionPort {

    /**
     * 在平台运维会话内执行一段逻辑。
     *
     * @param action 会话内动作
     * @param <T>    返回类型
     * @return 动作的返回值
     * @throws cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException
     *         平台运维角色未接入（fail-closed）
     */
    <T> T inPlatformScopeSession(@NonNull PlatformOpsAction<T> action);

    /**
     * 平台运维会话内的动作。
     *
     * <p>⚠️ <b>刻意不暴露 {@code DataSource}</b>：运维数据源只能由基础设施层持有，
     * 应用层拿到它就等于拿到了一条不受 RLS 约束的连接句柄。
     * 应用层只需要「在会话里跑这段逻辑」这一个能力。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    interface PlatformOpsAction<T> {
        /**
         * @return 返回值
         */
        T run();
    }
}
