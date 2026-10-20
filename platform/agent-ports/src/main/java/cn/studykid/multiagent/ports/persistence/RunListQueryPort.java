package cn.studykid.multiagent.ports.persistence;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Run 列表查询端口（P4-D13 / G7 · FE-01 §P7 检索台）。
 *
 * <p><b>为什么必须新建而不能复用 {@link RunQueryPort}</b>：
 * {@code RunQueryPort} 是<b>按 runId 查单条</b>，本端口是<b>按条件查一批 + 游标翻页</b>，
 * 两者的分页、排序、可见字段都不同。硬塞进同一个接口会让单条查询背上分页参数。
 *
 * <p>⚠️ <b>实现约束</b>（与 {@code RunQueryPort} 同源）：
 * <ul>
 *   <li>SQL <b>必须显式携带 {@code tenant_id}</b>——RLS 是第二道防线，不是唯一一道；</li>
 *   <li><b>不得返回 {@code input_payload}</b>（学生题目与解答）与
 *       {@code error_message}（内部错误细节），只返回 {@code error_code}；</li>
 *   <li>排序必须为 {@code (created_at DESC, run_id DESC)}<b>全序</b>，
 *       否则同毫秒多行在翻页时会重复或漏读。</li>
 * </ul>
 */
public interface RunListQueryPort {

    /**
     * 查询 Run 列表（游标分页）。
     *
     * @param tenantId 租户标识；<b>必须来自认证上下文</b>
     * @param state    状态过滤；{@code null} 表示不限
     * @param from     起始时刻（含）；{@code null} 表示不限
     * @param to       结束时刻（含）；{@code null} 表示不限
     * @param cursor   起始游标；{@code null} 表示第一页
     * @param limit    页大小（调用方已校验上限）
     */
    @NonNull Page<RunListItem> list(@NonNull String tenantId,
                                    @Nullable String state,
                                    @Nullable Instant from,
                                    @Nullable Instant to,
                                    @Nullable String cursor,
                                    int limit);

    /**
     * Run 列表条目。
     *
     * @param runId       Run 标识
     * @param state       Run 状态
     * @param createdAt   创建时刻（排序键第一分量）
     * @param completedAt 完成时刻；非终态为 {@code null}
     * @param attempt     领取次数
     * @param errorCode   失败原因<b>码</b>；<b>不是</b>错误原文
     */
    record RunListItem(
            @NonNull String runId,
            @NonNull String state,
            @NonNull Instant createdAt,
            @Nullable Instant completedAt,
            int attempt,
            @Nullable String errorCode
    ) {
    }
}