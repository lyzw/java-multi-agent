package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigPointer;
import cn.studykid.multiagent.core.cap.ConfigRelease;

/**
 * 当前发布指针端口（§6.3 第 3/5 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>本端口是「当前生效配置」的唯一权威入口</b>：
 * 读路径必须经由 {@link #current} 拿到指针指向的 release，
 * 再由其 {@code object_versions} 解析 id→精确版本。
 * <b>不得</b>用「某对象最新已发布版本行」代替（06 §3.2 / §6.3 第 2 条）。
 *
 * <p>⚠️ <b>CAS 的语义是「基于 expected 基线的单行更新」</b>：
 * 只有当指针当前的 {@code current_publish_seq} 与 expected 一致时更新才生效。
 * 失败方必须<b>变基重试</b>（§6.3 第 4 条），<b>不得</b>改用无条件覆盖 ——
 * 无条件覆盖会让并发发布互相吞掉，最终指针指向谁完全不可预测。
 *
 * <p>⚠️ <b>expected 为 {@code null} 表示「尚未发布过」</b>，
 * 这是首次发布的合法期望值，实现必须用 {@code IS NOT DISTINCT FROM} 比较，
 * 不可写成 {@code = NULL}（那恒为 UNKNOWN，会让首次发布永远失败）。
 */
public interface ConfigPointerPort {

    /**
     * 读取当前指针。
     *
     * @param scope    作用域
     * @param tenantId 租户标识；PLATFORM 作用域传 {@code null}
     * @return 指针；该作用域从未发布过时返回 {@link Optional#empty()}
     */
    @NonNull Optional<ConfigPointer> current(ConfigRelease.@NonNull Scope scope,
                                             @Nullable String tenantId);

    /**
     * CAS 移动当前指针（§6.3 第 3 条）。
     *
     * <p>⚠️ <b>失败必须整事务回滚</b>，不得留下半成品当前发布（PUB-1）。
     * 本方法只负责「移动指针」，事务边界由调用方（发布服务）持有 ——
     * 这样「版本行插入 + release 记录 + 指针 CAS」才能落在**同一事务**里。
     *
     * @param scope             作用域
     * @param tenantId          租户标识；PLATFORM 作用域传 {@code null}
     * @param expectedPublishSeq CAS 基线；{@code null} 表示「期望尚未发布过」
     * @param newReleaseId      新的发布头 id
     * @param newPublishSeq     新的发布序号
     * @return {@code true} 表示本次 CAS 成功；{@code false} 表示基线已变（调用方须变基重试）
     */
    boolean compareAndSet(ConfigRelease.@NonNull Scope scope,
                           @Nullable String tenantId,
                           @Nullable Long expectedPublishSeq,
                           long newReleaseId,
                           long newPublishSeq);
}
