package cn.studykid.multiagent.infrastructure.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ModelGateway;
import cn.studykid.multiagent.ports.model.ModelRequest;
import cn.studykid.multiagent.ports.model.ModelResponse;

/**
 * 模型网关契约测试（P1-07 / FR-007 / AT-MODEL-001 与 AT-MODEL-002）。
 *
 * <p><b>验收映射</b>（P0-D04）：
 * <ul>
 *   <li>AT-MODEL-001 —— 超时、限流、供应商错误被归一化；</li>
 *   <li>AT-MODEL-002 —— 秘密不出现在日志。</li>
 * </ul>
 *
 * <p><b>为何用 Fake 而非真实供应商</b>：契约测试需在 CI 无凭据环境运行，
 * 且不能因供应商行为变更而失效。Fake 模拟的异常消息中<b>刻意含假密钥</b>——
 * 若脱敏被绕过，本测试即失败。
 */
class ModelGatewayContractTest {

    private static ModelRequest request() {
        return new ModelRequest(
                "test-model",
                "你是初中数学辅导助手。",
                "解方程 x^2-5x+6=0",
                0.7d, 1024, Duration.ofSeconds(2), Map.of());
    }

    @Nested
    @DisplayName("AT-MODEL-001：错误归一化")
    class ErrorNormalization {

        @Test
        @DisplayName("✅ 正常调用返回归一化响应与 usage")
        void normalCall() {
            var gw = FakeModelGateway.responding("x=2 或 x=3", 120, 45);
            ModelResponse resp = gw.complete(request());

            assertThat(resp.content()).isEqualTo("x=2 或 x=3");
            assertThat(resp.finishReason()).isEqualTo(ModelResponse.FinishReason.STOP);
            assertThat(resp.usage().totalTokens())
                    .as("usage 必须回传供预算结算（FR-012）")
                    .isEqualTo(165);
        }

        @Test
        @DisplayName("✅ 超时 → TIMEOUT 且标记为可重试")
        void timeoutNormalized() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.TIMEOUT, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind()).isEqualTo(ModelGateway.ModelErrorKind.TIMEOUT);
            assertThat(e.kind().retryable())
                    .as("超时无副作用前提，可退避重试")
                    .isTrue();
        }

        @Test
        @DisplayName("✅ 限流 → RATE_LIMITED 且可重试")
        void rateLimitedNormalized() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.RATE_LIMITED, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind()).isEqualTo(ModelGateway.ModelErrorKind.RATE_LIMITED);
            assertThat(e.kind().retryable()).isTrue();
        }

        @Test
        @DisplayName("✅ 供应商错误 → PROVIDER_ERROR 且可重试")
        void providerErrorNormalized() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.PROVIDER_ERROR, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind()).isEqualTo(ModelGateway.ModelErrorKind.PROVIDER_ERROR);
            assertThat(e.kind().retryable()).isTrue();
        }

        @Test
        @DisplayName("✅ 上下文超限 → INVALID_REQUEST 且不可重试")
        void invalidRequestNotRetryable() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.INVALID_REQUEST, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind()).isEqualTo(ModelGateway.ModelErrorKind.INVALID_REQUEST);
            assertThat(e.kind().retryable())
                    .as("⚠️ 误判为可重试会导致无限重试烧钱")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 鉴权失败 → UNAUTHORIZED 且不可重试")
        void unauthorizedNotRetryable() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.UNAUTHORIZED, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind()).isEqualTo(ModelGateway.ModelErrorKind.UNAUTHORIZED);
            assertThat(e.kind().retryable())
                    .as("凭据错误重试无意义，只会触发封禁")
                    .isFalse();
        }

        @Test
        @DisplayName("✅ 内容拦截 → CONTENT_BLOCKED 且不可重试")
        void contentBlockedNotRetryable() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.CONTENT_BLOCKED, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind()).isEqualTo(ModelGateway.ModelErrorKind.CONTENT_BLOCKED);
            assertThat(e.kind().retryable()).isFalse();
        }

        @Test
        @DisplayName("✅ 未识别异常兜底为 PROVIDER_ERROR，不泄漏供应商类型")
        void unknownExceptionDoesNotLeak() {
            var gw = new AbstractModelGateway() {
                @Override
                protected ModelResponse invokeGuarded(ModelRequest request) {
                    throw new IllegalStateException("com.vendor.sdk.WeirdError: xxx");
                }
            };
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.kind())
                    .as("未识别异常必须兜底，不得让供应商异常类型泄漏到领域层")
                    .isEqualTo(ModelGateway.ModelErrorKind.PROVIDER_ERROR);
        }
    }

    @Nested
    @DisplayName("AT-MODEL-002：凭据不出现在日志与异常消息")
    class CredentialRedaction {

        @Test
        @DisplayName("✅ 异常消息中的 API Key 被脱敏")
        void apiKeyRedactedInException() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.UNAUTHORIZED, null);
            var e = assertThrows(() -> gw.complete(request()));

            assertThat(e.getMessage())
                    .as("⚠️ 凭据泄漏进异常消息 = 泄漏进日志（Sec-0 级合规问题）")
                    .doesNotContain("sk-fake1234567890abcdefghijklmn");
        }

        @Test
        @DisplayName("✅ 限流消息中的 api-key 被脱敏")
        void apiKeyRedactedInRateLimitMessage() {
            var gw = new FakeModelGateway(FakeModelGateway.FaultMode.RATE_LIMITED, null);
            var e = assertThrows(() -> gw.complete(request()));
            assertThat(e.getMessage()).doesNotContain("sk-fake1234567890abcdefghijklmn");
        }

        @Test
        @DisplayName("✅ Authorization 头被脱敏但保留键名")
        void authorizationHeaderRedacted() {
            var raw = "HTTP 500; Authorization: Bearer sk-fake1234567890abcdefghijklmn";
            String redacted = CredentialRedactor.redact(raw);
            assertThat(redacted)
                    .doesNotContain("sk-fake1234567890abcdefghijklmn")
                    .contains("Authorization");
        }

        @Test
        @DisplayName("✅ 各种密钥形态均被识别")
        void variousKeyFormatsRedacted() {
            String[] secrets = {
                    "sk-abcdefgh12345",
                    "ghp_abcdefgh1234567890",
                    "AKIAIOSFODNN7EXAMPLE",
                    "xoxb-1234567890-abcdef",
            };
            for (String secret : secrets) {
                assertThat(CredentialRedactor.redact("key=" + secret))
                        .as("未脱敏: %s", secret)
                        .doesNotContain(secret);
            }
        }

        @Test
        @DisplayName("✅ 普通业务内容不被误伤")
        void normalContentUnaffected() {
            String text = "解方程 x^2-5x+6=0，答案是 x=2 或 x=3";
            assertThat(CredentialRedactor.redact(text)).isEqualTo(text);
        }

        @Test
        @DisplayName("✅ null 与空串安全处理")
        void nullSafe() {
            assertThat(CredentialRedactor.redact(null)).isNull();
            assertThat(CredentialRedactor.redact("")).isEmpty();
        }

        @Test
        @DisplayName("⚠️ safeMessage 不携带原始 cause（避免堆栈绕过脱敏）")
        void safeMessageDoesNotLeakCause() {
            var cause = new RuntimeException("token=sk-abcdefgh12345678");
            String msg = CredentialRedactor.safeMessage("调用失败", cause);
            assertThat(msg)
                    .doesNotContain("sk-abcdefgh12345678")
                    .contains("调用失败");
        }
    }

    @Nested
    @DisplayName("请求校验")
    class RequestValidation {

        @Test
        @DisplayName("✅ modelId 为空被拒绝")
        void blankModelIdRejected() {
            assertThatThrownBy(() -> new ModelRequest(
                    " ", "sys", "user", 0.7d, 100, Duration.ofSeconds(1), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ 非正 timeout 被拒绝")
        void nonPositiveTimeoutRejected() {
            assertThatThrownBy(() -> new ModelRequest(
                    "m", "sys", "user", 0.7d, 100, Duration.ZERO, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("✅ toolSchemas 为 null 视为空")
        void nullToolSchemasTolerated() {
            var req = new ModelRequest("m", "sys", "user", 0.7d, 100,
                    Duration.ofSeconds(1), null);
            assertThat(req.toolSchemas()).isEmpty();
        }
    }

    @Nested
    @DisplayName("usage 与预算")
    class UsageAccounting {

        @Test
        @DisplayName("✅ usage 为 0 时合法（部分供应商不计费）")
        void zeroUsageAllowed() {
            var resp = new ModelResponse("", Map.of(),
                    new ModelResponse.ModelUsage(0, 0),
                    ModelResponse.FinishReason.STOP);
            assertThat(resp.usage().totalTokens()).isZero();
        }

        @Test
        @DisplayName("✅ 负 token 被拒绝")
        void negativeTokensRejected() {
            assertThatThrownBy(() -> new ModelResponse.ModelUsage(-1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────
    private static ModelGateway.ModelGatewayException assertThrows(
            Runnable action) {
        try {
            action.run();
        } catch (ModelGateway.ModelGatewayException e) {
            return e;
        }
        throw new AssertionError("预期抛出 ModelGatewayException 但未抛出");
    }
}