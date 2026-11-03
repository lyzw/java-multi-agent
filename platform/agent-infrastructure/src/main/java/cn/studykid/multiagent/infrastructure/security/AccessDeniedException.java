package cn.studykid.multiagent.infrastructure.security;

/**
 * 访问被拒异常 —— 鉴权 Deny 在门禁层（{@code AccessGate}）的显式抛出形态（fail-closed）。
 *
 * <p>HTTP 层据此返回 403/500，<b>绝不</b>回落为放行。原因码供审计与指标分类使用。
 */
public final class AccessDeniedException extends RuntimeException {

    private final String reasonCode;

    public AccessDeniedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public AccessDeniedException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
