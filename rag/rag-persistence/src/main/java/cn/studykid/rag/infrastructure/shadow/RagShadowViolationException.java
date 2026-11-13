package cn.studykid.rag.infrastructure.shadow;

/**
 * 影子期 P2 阶段阻断信号（fail-closed）。M2/P1 影子期<b>不会</b>抛出——
 * 仅记录日志与计数，绝不改变请求结果（见 {@link RagShadowReporter}）。
 */
public class RagShadowViolationException extends RuntimeException {

    public RagShadowViolationException(String code, String detail) {
        super(code + ": " + detail);
    }
}
