package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;

/**
 * 配置对象生命周期状态迁移端口（§6.6 / 06 §3 · W-PERM-P5）。
 *
 * <p>⚠️ <b>为什么单独一个端口</b>：
 * 生命周期状态是<b>流程状态</b>，与对象定义的<b>内容</b>是两件事。
 * 已发布版本行的内容不可改写（§6.1），但它的生命周期可以从
 * PUBLISHED 迁到 DEPRECATED（06 §3.4「被引用对象禁止物理删除 → 改 DEPRECATED」）。
 * 把这个迁移塞进对象定义端口，会让「读定义」与「改状态」混成一个语义。
 *
 * <p>⚠️ <b>迁移必须带「期望原状态」作为前置条件</b>：
 * 这是 CAS 语义 —— 避免两个并发操作都以为自己从 DRAFT 出发
 * （一个想发布、一个想停用）而互相覆盖。
 */
public interface ConfigLifecyclePort {

    /**
     * 迁移对象版本行的生命周期状态。
     *
     * @param type        对象类型标识（{@code tool} / {@code skill} / {@code mcp_server} /
     *                     {@code agent} / {@code result_contract} / {@code routing_policy}）
     * @param tenantId    租户标识；租户级对象（agent / result_contract / routing_policy）需要
     * @param objectId    对象逻辑 id
     * @param version     版本号
     * @param expectedFrom 期望的原状态（CAS 前置条件）
     * @param to          目标状态
     * @return {@code true} 表示迁移成功；{@code false} 表示原状态不符或对象不存在
     */
    boolean transition(@NonNull String type,
                       @Nullable String tenantId,
                       @NonNull String objectId,
                       int version,
                       @NonNull ConfigLifecycleState expectedFrom,
                       @NonNull ConfigLifecycleState to);

    /**
     * 读取对象版本行当前的生命周期状态。
     *
     * @param type     对象类型标识（见 {@link #transition}）
     * @param tenantId 租户标识；租户级对象（agent / result_contract / routing_policy）需要
     * @param objectId 对象逻辑 id
     * @param version  版本号
     * @return 当前状态；对象不存在时为 {@link Optional#empty()}
     */
    @NonNull
    Optional<ConfigLifecycleState> stateOf(@NonNull String type,
                                           @Nullable String tenantId,
                                           @NonNull String objectId,
                                           int version);
}
