package cn.studykid.multiagent.ports.observer;

import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.model.ModelGateway.ModelGatewayException;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 模型调用观测者（观测每次模型调用的完整生命周期）。
 *
 * <p><b>注入点</b>：{@code ModelGateway.complete()} 前后，由
 * {@code ObservableModelGateway} 包装器调用。
 *
 * <p><b>关键纪律</b>：
 * <ul>
 *   <li>观测者异常<b>不得影响主流程</b>——与 {@code RunEventPort} 同纪律，
 *       异常只记 WARN，不冒泡；</li>
 *   <li>观测数据中的任何文本必须过 {@code CredentialRedactor} 脱敏；</li>
 *   <li>不输出 API Key、Authorization 等凭据原文。</li>
 * </ul>
 *
 * <p><b>观测者链</b>：多个观测者按 Spring {@code @Order} 顺序执行，
 * 建议顺序：日志 → 指标 → 事件桥接。
 *
 * @see ObservableModelGateway
 */
public interface ModelCallObserver {

    /**
     * 模型调用前触发。
     *
     * @param tenantId  租户标识
     * @param runId     Run 标识
     * @param step      当前轮次（从 1 开始）
     * @param request   即将发送的请求（已脱敏）
     */
    void beforeCall(@NonNull String tenantId, @NonNull String runId, int step,
                    @NonNull ModelRequest request);

    /**
     * 模型调用成功后触发。
     *
     * @param tenantId  租户标识
     * @param runId     Run 标识
     * @param step      当前轮次（从 1 开始）
     * @param request   已发送的请求
     * @param response  响应结果
     * @param latency   调用耗时（含网络 + 解析）
     */
    void afterCall(@NonNull String tenantId, @NonNull String runId, int step,
                   @NonNull ModelRequest request, @NonNull ModelResponse response,
                   @NonNull Duration latency);

    /**
     * 模型调用异常后触发。
     *
     * @param tenantId  租户标识
     * @param runId     Run 标识
     * @param step      当前轮次（从 1 开始）
     * @param request   已发送的请求
     * @param exception 归一化后的异常（kind 已确定）
     * @param latency   失败前的耗时（可能很短）
     */
    void onException(@NonNull String tenantId, @NonNull String runId, int step,
                     @NonNull ModelRequest request, @NonNull ModelGatewayException exception,
                     @NonNull Duration latency);
}
