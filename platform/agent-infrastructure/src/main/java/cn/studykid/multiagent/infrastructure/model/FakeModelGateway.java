package cn.studykid.multiagent.infrastructure.model;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 模拟供应商适配器（P1-07 PoC 专用）。
 *
 * <p><b>用途</b>：让契约测试能在<b>无网络、无真实凭据</b>的前提下验证
 * 「归一化」与「脱敏」是否正确。若直接对接真实供应商做测试，
 * 既需要凭据，也会因供应商行为变更而使测试不稳定。
 *
 * <p><b>注意</b>：这是 PoC 的测试替身，<b>不得用于生产</b>。
 * 真实实现继承 {@link AbstractModelGateway} 并覆写 {@code invokeGuarded}。
 *
 * <p>⚠️ 模拟的异常消息中<b>刻意包含假密钥</b>，用于验证脱敏是否真的生效——
 * 若脱敏被绕过，契约测试会失败。
 */
public class FakeModelGateway extends AbstractModelGateway {

    /** 故障注入模式。 */
    public enum FaultMode {
        /** 正常返回。 */
        NONE,
        /** 超时。 */
        TIMEOUT,
        /** 限流（429）。 */
        RATE_LIMITED,
        /** 供应商内部错误（500）。 */
        PROVIDER_ERROR,
        /** 上下文超限（400，重试无意义）。 */
        INVALID_REQUEST,
        /** 鉴权失败（401）。 */
        UNAUTHORIZED,
        /** 内容被拦截。 */
        CONTENT_BLOCKED
    }

    /** 假密钥——仅用于验证脱敏，不对应任何真实凭据。 */
    private static final String FAKE_SECRET = "sk-fake1234567890abcdefghijklmn";

    private final FaultMode mode;
    private final ModelResponse cannedResponse;

    public FakeModelGateway(FaultMode mode, ModelResponse cannedResponse) {
        this.mode = mode;
        this.cannedResponse = cannedResponse;
    }

    /** 正常响应的便捷构造。 */
    public static FakeModelGateway responding(@NonNull String content, long in, long out) {
        return new FakeModelGateway(FaultMode.NONE, new ModelResponse(
                content, Map.of(),
                new ModelResponse.ModelUsage(in, out),
                ModelResponse.FinishReason.STOP));
    }

    @Override
    protected ModelResponse invokeGuarded(ModelRequest request) {
        return callWithTimeout(() -> {
            switch (mode) {
                case TIMEOUT:
                    // 模拟阻塞直至超时：睡到远超 timeout 为止，
                    // 由外层 callWithTimeout 的超时控制中断它。
                    try {
                        Thread.sleep(request.timeout().toMillis() + 500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ModelGatewayException(ModelErrorKind.TIMEOUT,
                                "调用被中断");
                    }
                    throw new ModelGatewayException(ModelErrorKind.TIMEOUT,
                            "upstream timeout after " + request.timeout().toMillis() + "ms");

                case RATE_LIMITED:
                    // 模拟供应商 429，且消息中带假密钥（验证脱敏）
                    throw new ModelGatewayException(ModelErrorKind.RATE_LIMITED,
                            "HTTP 429 rate_limit_exceeded; api-key=" + FAKE_SECRET);

                case PROVIDER_ERROR:
                    throw new ModelGatewayException(ModelErrorKind.PROVIDER_ERROR,
                            "HTTP 500 internal_error; Authorization: Bearer " + FAKE_SECRET);

                case INVALID_REQUEST:
                    throw new ModelGatewayException(ModelErrorKind.INVALID_REQUEST,
                            "context length exceeded");

                case UNAUTHORIZED:
                    throw new ModelGatewayException(ModelErrorKind.UNAUTHORIZED,
                            "invalid api key: " + FAKE_SECRET);

                case CONTENT_BLOCKED:
                    throw new ModelGatewayException(ModelErrorKind.CONTENT_BLOCKED,
                            "content blocked by safety policy");

                case NONE:
                default:
                    return cannedResponse;
            }
        }, request.timeout());
    }

    /** 供子类覆写以验证 classify：把 SDK 异常映射为归一化分类。 */
    public static ModelErrorKind classifyVendorError(Throwable error) {
        return new FakeModelGateway(FaultMode.NONE, null).classify(error);
    }
}