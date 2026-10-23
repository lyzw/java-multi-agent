package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 配置发布事务边界端口（§6.3 第 1 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>为什么需要它</b>：
 * §6.3 第 1 条把「原子发布」定义为<b>唯一</b>的成功条件：
 * <b>版本行插入 + release 记录 + 当前指针 CAS 同事务提交</b>。
 * 这三步分属三个不同的仓储，若各自开各自的事务，
 * 就可能出现「版本行已插入、指针未移动」的半成品当前发布 ——
 * 那正是 PUB-1 要防的失败形态。
 *
 * <p>本端口把事务边界从「实现内部自行 commit」提升为<b>显式契约</b>：
 * 实现方在 {@link #execute} 内复用同一连接与同一事务，
 * 动作抛出异常即整事务回滚。
 *
 * <p>⚠️ <b>租户绑定是事务的一部分</b>：
 * 实现必须在事务内、且在任何业务 SQL 之前绑定 {@code app.tenant_id}
 * （RLS 前置条件）。PLATFORM 作用域传 {@code null} 租户标识，
 * 由实现绑定零 UUID 哨兵（与 {@code cap_config_release} 的既有处置一致）。
 */
public interface ConfigPublishTransaction {

    /**
     * 在事务中执行一段动作。
     *
     * @param tenantId 租户标识；PLATFORM 作用域传 {@code null}
     * @param action   事务内动作（抛异常即整事务回滚）
     * @return 动作返回值
     */
    <T> T execute(@Nullable String tenantId, @NonNull Action<T> action);

    /** 事务内动作。 */
    @FunctionalInterface
    interface Action<T> {
        /**
         * @return 动作返回值
         */
        T run();
    }
}
