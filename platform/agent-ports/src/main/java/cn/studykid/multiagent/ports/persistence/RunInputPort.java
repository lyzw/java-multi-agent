package cn.studykid.multiagent.ports.persistence;

import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * Run 输入载荷的<b>执行侧</b>读取口（L-4 闭合）。
 *
 * <p>⚠️ <b>与 {@link RunQueryPort} 故意分开</b>：查询侧的 Run 视图
 * <b>不得</b>返回 {@code input_payload}（含学生提交的题目与解答，属敏感载荷），
 * 而执行侧<b>必须</b>拿到它 —— 否则模型回答的永远是装配层写死的占位问题，
 * 用户提交的内容根本没进过上下文。
 *
 * <p>因此本端口只允许由 Worker 执行链持有，
 * <b>不得</b>注入任何 API 控制器或治理查询侧。
 */
public interface RunInputPort {

    /**
     * 读取该 Run 创建时落库的输入。
     *
     * <p>fail-closed 由调用方决定：Run 不存在或输入为空时返回<b>空 Map</b>，
     * 本端口不猜测「该用什么默认输入」——默认输入就是把假问题当真问题。
     *
     * @param tenantId 租户（RLS 绑定依据，跨租户读取返回空）
     * @param runId    Run 标识
     * @return 输入载荷；缺失时为空 Map
     */
    @NonNull Map<String, Object> inputOf(@NonNull String tenantId, @NonNull String runId);
}
