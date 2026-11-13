package cn.studykid.rag.infrastructure.shadow;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 影子观测静态门面（采集点统一入口）。
 *
 * <p>各仓储读路径调用本类，<b>不感知</b>是否启用影子：未注册采集器时全部 no-op。
 * 启用时由 {@link RagShadowCollector#attach()} 注入采集器（通过 Spring 工厂方法）。
 *
 * <p>设计要点（与平台 {@code AuthzShadowHooks} 一致）：比对/染色逻辑只有这一份实现，
 * 不存在「某个仓库自己写一份」的旁路 —— 守卫测试可扫描并否决自建实现。
 */
public final class RagShadow {

    private static volatile RagShadowCollector INSTANCE = null;

    private RagShadow() {
    }

    static void register(@NonNull RagShadowCollector collector) {
        INSTANCE = collector;
    }

    static void unregister() {
        INSTANCE = null;
    }

    public static boolean isEnabled() {
        return INSTANCE != null;
    }

    /** 采集点：一次读路径样本（如仓库的检索/管理查询入口）。 */
    public static void collect(@NonNull String resourceType) {
        RagShadowCollector c = INSTANCE;
        if (c == null) {
            return;
        }
        if (c.reporter().shouldEvaluate(resourceType, null)) {
            c.reporter().recordShadowSample(resourceType);
        }
    }

    /** 采集点：一次仍走 RLS 兼容层（set_config GUC bind）的读路径。 */
    public static void recordRlsBind(@NonNull String resourceType) {
        RagShadowCollector c = INSTANCE;
        if (c == null) {
            return;
        }
        if (c.reporter().shouldEvaluate(resourceType, null)) {
            c.reporter().reportRlsBind(resourceType);
        }
    }

    /** 上报比对不一致（显式谓词结果 ≠ RLS 结果）。 */
    public static void reportMismatch(@NonNull String resourceType, @NonNull String detail) {
        RagShadowCollector c = INSTANCE;
        if (c == null) {
            return;
        }
        c.reporter().reportMismatch(resourceType, detail);
    }

    /** 上报染色违例（跨租户泄露）。 */
    public static void reportTaintViolation(@NonNull String resourceType, @NonNull String rowTenantId) {
        RagShadowCollector c = INSTANCE;
        if (c == null) {
            return;
        }
        c.reporter().reportTaintViolation(resourceType, rowTenantId);
    }

    /** 当前采集器（可为 null）。供周报/自检读取快照。 */
    @Nullable
    public static RagShadowCollector current() {
        return INSTANCE;
    }
}
