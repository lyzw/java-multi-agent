package cn.studykid.multiagent.infrastructure.model;

import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 模型适配器基类（P1-07 PoC / FR-007）。
 *
 * <p><b>PoC 的目的</b>不是对接真实供应商，而是把「归一化」这件事做对：
 * <ul>
 *   <li>AT-MODEL-001 —— 各家 SDK 的异常形态千差万别，必须收敛为
 *       {@link ModelErrorKind} 六个枚举值，且 retryable 属性正确；</li>
 *   <li>AT-MODEL-002 —— 凭据不得出现在日志与异常消息中。</li>
 * </ul>
 *
 * <p><b>为什么把归一化放在基类</b>：不同供应商的差异只在
 * 「怎么识别超时/限流/鉴权失败」这一步。若让每个适配器各自实现整个
 * {@code complete()}，漏掉某个分支的概率很高——而漏掉的后果是
 * 把「不可重试」误判为「可重试」，触发无限重试。
 */
public abstract class AbstractModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractModelGateway.class);

    @Override
    public @NonNull ModelResponse complete(@NonNull ModelRequest request) {
        try {
            return invokeGuarded(request);
        } catch (ModelGatewayException e) {
            // ⚠️ 已归一化的异常必须<b>原样保留 kind</b>。
            // 若在此重新归一化或兜底为 PROVIDER_ERROR，会把
            // 「不可重试」的分类（如 CONTENT_BLOCKED/UNAUTHORIZED）
            // 误标为可重试 → 触发无限重试。
            // 仅补上脱敏后的上下文，不改变分类。
            throw new ModelGatewayException(e.kind(),
                    CredentialRedactor.safeMessage(
                            "模型调用失败[" + e.kind() + "]", e.getCause()));
        } catch (RuntimeException e) {
            // ⚠️ 兜底：任何未被归一化的异常一律视为 PROVIDER_ERROR，
            // 绝不让供应商专属异常类型泄漏到领域层。
            log.warn("模型调用出现未归一化异常: {}", CredentialRedactor.redactMessage(e));
            throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR,
                    CredentialRedactor.safeMessage("模型调用失败[PROVIDER_ERROR]", e));
        }
    }

    /**
     * 实际调用，须由子类实现。
     *
     * <p>实现方<b>必须</b>把供应商异常转为 {@link ModelGatewayException}；
     * 未转的异常会落入 {@link #complete} 的兜底分支。
     */
    protected abstract ModelResponse invokeGuarded(@NonNull ModelRequest request);

    /**
     * 归一化供应商异常为 {@link ModelErrorKind}。
     *
     * <p>子类按自身 SDK 的特征覆写 {@link #classify}。
     *
     * @param error 供应商异常
     * @return 归一化分类
     */
    protected ModelErrorKind classify(@NonNull Throwable error) {
        if (error instanceof TimeoutException) {
            return ModelErrorKind.TIMEOUT;
        }
        if (error instanceof java.net.http.HttpTimeoutException) {
            return ModelErrorKind.TIMEOUT;
        }
        return ModelErrorKind.PROVIDER_ERROR;
    }

    /**
     * 以超时约束执行供应商调用。
     *
     * @param supplier 供应商调用
     * @param timeout  超时时长
     */
    protected ModelResponse callWithTimeout(
            @NonNull Supplier<ModelResponse> supplier,
            @NonNull Duration timeout) {
        CompletableFuture<ModelResponse> future =
                CompletableFuture.supplyAsync(supplier);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ModelGatewayException(ModelErrorKind.TIMEOUT,
                    CredentialRedactor.safeMessage("模型调用超时", e));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();

            // ⚠️ 关键：若已是归一化异常，必须<b>原样上抛</b>。
            // 否则会走 classify() 被降级为 PROVIDER_ERROR，把
            // 「不可重试」的分类误标为可重试 → 无限重试。
            if (cause instanceof ModelGatewayException already) {
                throw already;
            }
            // 供应商原生异常在此归一化——领域层只见 ModelErrorKind
            throw new ModelGatewayException(classify(cause),
                    CredentialRedactor.safeMessage("模型调用异常", cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR,
                    CredentialRedactor.safeMessage("模型调用被中断", e));
        }
    }

}