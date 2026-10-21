package cn.studykid.multiagent.api.governance;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

import cn.studykid.multiagent.core.tenant.TenantContext;
import cn.studykid.multiagent.ports.persistence.Page;
import cn.studykid.multiagent.ports.persistence.RateLimitPort;

/**
 * 治理端点的横切支撑（P4-D13 / G7~G10）。
 *
 * <p>把限流、页大小校验、时间参数解析集中在一处，
 * 是为了让四个端点<b>对这批易错决策只有一份实现</b>——
 * FE-01 §P7~P10 明确要求分页契约「先冻结再开工」，
 * 而契约一旦分散在四个控制器里，冻结的就只是纸面。
 * <p>⚠️ <b>public 是因为不再只服务 governance 包</b>（P4-D15 新增
 * {@code ConfigOverrideController} 同在 api 层但不同包，复用同一套
 * 限流与分页语义）。保持包私有会迫使新端点复制一份限流判断，
 * 而两份实现漂移的后果是「有的端点限流、有的不限流」。
 */
public final class GovernanceSupport {

    /** 默认页大小。 */
    static final int DEFAULT_LIMIT = 20;

    /**
     * 页大小上限。
     *
     * <p>⚠️ <b>超限必须 400 而不是静默截断</b>：静默截断会让前端以为
     * 「这个租户一共就这么多条数据」，而真相是「你只拿到了前 100 条」。
     */
    static final int MAX_LIMIT = 100;

    private GovernanceSupport() {
    }

    /**
     * 校验并归一化页大小。
     *
     * @throws IllegalArgumentException 越界 → 由全局处理器转 <b>400</b>
     */
    public static int limit(@Nullable Integer requested) {
        int value = requested == null ? DEFAULT_LIMIT : requested;
        if (value <= 0) {
            throw new IllegalArgumentException("limit 必须为正数: " + value);
        }
        if (value > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit 超过上限 " + MAX_LIMIT + "（反证 A-3：上限不是性能建议，是契约）");
        }
        return value;
    }

    /**
     * 解析 ISO-8601 时刻。
     *
     * @throws IllegalArgumentException 格式非法 → <b>400</b>
     */
    @Nullable
    static Instant instant(@Nullable String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    paramName + " 必须是 ISO-8601 时刻（如 2026-10-21T00:00:00Z）");
        }
    }

    /** 时间区间合法性：from 不得晚于 to。 */
    static void checkRange(@Nullable Instant from, @Nullable Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("createdFrom/from 不得晚于 createdTo/to");
        }
    }

    /**
     * 取令牌限流决策。
     *
     * <p>⚠️ <b>{@code FAIL_CLOSED} 与 {@code REJECTED} 同为 429</b>：
     * 限流组件故障时放行等于限流形同虚设（P3-D08 D-4），
     * 但把它报成 500 会让前端以为是系统故障而触发重试风暴。
     * 429 的语义就是「现在不行，稍后重试」，对两种情况都成立。
     */
    public static boolean rateLimited(@NonNull RateLimitPort limiter,
                               @NonNull TenantContext context,
                               @NonNull String bucket) {
        return limiter.check(context.tenantId(), bucket) != RateLimitPort.Decision.ALLOWED;
    }

    /**
     * 429 响应。
     *
     * <p>⚠️ <b>必须是泛型方法</b>：控制器方法的返回类型各不相同
     * （{@code PageResponse<X>} / {@code DecisionResponse}），
     * 固定返回 {@code ResponseEntity<Void>} 会让限流分支无法编译。
     */
    public static <T> ResponseEntity<T> tooManyRequests() {
        return ResponseEntity.status(429).header("Retry-After", "60").build();
    }

    /**
     * 枚举值白名单校验。
     *
     * <p>⚠️ <b>非法值必须 400，不得静默返回空列表</b>：
     * 前端传 {@code state=UNKNOWN} 而该状态并不存在于 {@code run_state} 时，
     * 返回空列表会让运维以为「这个状态下没有 Run」，
     * 而真相是「这个状态就不存在」——两者会导向完全不同的排查方向。
     *
     * @param allowed 允许值集合；为 {@code null} 表示不校验
     */
    static void requireEnum(@Nullable String value,
                            java.util.Set<String> allowed,
                            String paramName) {
        if (value == null || value.isBlank() || allowed == null) {
            return;
        }
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(
                    paramName + " 取值非法: " + value + "（允许值: " + allowed + "）");
        }
    }

    /**
     * 一页响应。
     *
     * @param nextCursor 无更多时为 {@code null}——<b>「空页」与「没有更多」是两回事</b>
     */
    public record PageResponse<T>(@NonNull List<T> items, @Nullable String nextCursor) {
        public PageResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static <T> PageResponse<T> of(Page<T> page) {
            return new PageResponse<>(page.items(), page.nextCursor());
        }

        /**
         * 已把端口条目映射为响应 DTO 后的构造入口。
         *
         * <p>⚠️ <b>public 是因为 P4-D16 的配置变更页复用同一分页契约</b>：
         * 保持包私有会迫使新端点自定义一套分页响应，
         * 而两套实现漂移的后果是「有的端点能翻页、有的不能」。
         */
        public static <T> PageResponse<T> of(List<T> items, String nextCursor) {
            return new PageResponse<>(items, nextCursor);
        }
    }
}
