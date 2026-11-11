package cn.studykid.multiagent.ports.context;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

/**
 * 压缩状态 checkpoint 读写端口（《方案》§5.6 / §6.2-C，M6 端口契约）。
 *
 * <p>⚠️ <b>端口层保持中性</b>：只认识 {@link Checkpoint} / {@link CheckpointDelta}
 * 这两个不可信值对象，不依赖 {@code agent-context} 的算法层类型（ADR-011 / C1）。
 * 实现（M11 Repository）负责 PG + FORCE RLS + fencing token CAS。
 *
 * <p>⚠️ <b>fail-closed 语义</b>：
 * <ul>
 *   <li>{@link #load} 在未命中、已过期、{@code policyVersion} / {@code modelId} 不匹配、
 *       JSON 解析失败、digest 校验失败时一律返回 {@link Optional#empty()} 或
 *       {@link Checkpoint#empty()}，⛔ 绝不把不可信缓存当有效状态续写；</li>
 *   <li>{@link #advance} 是<b>条件写入</b>：仅当 {@code expectedToSeq} 与当前游标一致
 *       且 {@code fencingToken} 仍持有租约时才可写入，冲突返回 {@code false}；
 *       ⛔ 绝不覆盖更新的 checkpoint，绝不静默吞掉冲突；</li>
 *   <li>端口本身不提交事务（铁律 #1），事务边界由调用方持有。</li>
 * </ul>
 *
 * <p>⚠️ {@code stateJson} 是结构化状态的确定性 JSON，不含原始对话正文；
 * {@code sourceMessageIds} 只存可回取引用（PIPL 未成年人最小保留）。
 */
public interface ContextCheckpointPort {

    /**
     * 加载指定作用域下、指定策略版本的 checkpoint。
     *
     * @return 有效 checkpoint；未命中/失效/不可信一律 {@link Optional#empty()}
     */
    @NonNull Optional<Checkpoint> load(@NonNull String tenantId,
                                       @NonNull String scopeKey,
                                       @NonNull String policyVersion);

    /**
     * 按 CAS 语义推进 checkpoint 游标。
     *
     * @param expectedToSeq 期望的当前 {@code upToSeq}（空 checkpoint 用 {@code -1}）
     * @param fencingToken  执行租约令牌，防止陈旧 worker 回写
     * @return {@code true} 仅当条件命中且确实写入一行
     */
    boolean advance(@NonNull String tenantId,
                    @NonNull CheckpointDelta delta,
                    long expectedToSeq,
                    long fencingToken);
}
