package cn.studykid.multiagent.api.config;

/**
 * 单条配置的诚实视图。
 *
 * <p><b>为什么必须带 {@code effective}</b>：FE-02 §0.3 要求配置页区分
 * 「yml 声明值」与「运行时实际生效值」。本项目有大量 yml 键是摆设
 * （声明了但生产无读取点），若把声明值直接当成生效值展示，
 * 会让运维误以为「改了配置就改了行为」，实则毫无影响。
 *
 * <p><b>⚠️ 诚实边界</b>：{@code effective=false} 的条目，
 * {@code note} 必须说明「改后不影响行为」，前端据此渲染为
 * 「已声明 / 未接入运行时」，绝不可渲染成「已生效」。
 */
public record ConfigEntry(
        /** 配置键（与 application.yml 完全对应）。 */
        String key,
        /** yml 解析后的声明值；含环境变量覆盖。未声明时为 "(未声明)"。 */
        String declaredValue,
        /** 该键是否真正接入运行时（改变它会影响行为）。 */
        boolean effective,
        /** 声明来源（yml / yml(校验镜像) 等）。 */
        String source,
        /** 诚实说明：已接入 / 仅声明未接线 / 仅声明未确认。 */
        String note) {
}
