package cn.studykid.multiagent.api.security;

/**
 * 当前请求的可信身份来源。
 *
 * <p><b>这是解除「D1 端点不可部署」阻塞的核心抽象</b>。
 *
 * <p><b>为何必须存在</b>：D1 的授权闸门逻辑正确，但此前
 * {@code TenantContext} 由 Spring 按参数注入 —— 任何调用方都能构造。
 * 攻击者提交任意 {@code tenantId} 与 {@code roles}，
 * 授权闸门即形同虚设。
 *
 * <p><b>契约（三条，缺一即越权）</b>：
 * <ol>
 *   <li><b>只能来自已验证的令牌</b> —— 实现必须先验签再返回，
 *       不得信任请求头中的原始值。</li>
 *   <li><b>租户不可由调用方指定</b> —— 只接受令牌声明且与库中登记一致者。</li>
 *   <li><b>无令牌即拒绝</b> —— 不得返回「默认租户」或任何兜底值。</li>
 * </ol>
 *
 * <p>⚠️ 控制器<b>只能</b>通过本接口取上下文。
 * 任何新增的控制器若直接接收 {@code TenantContext} 参数，
 * 即重新引入了「上下文可伪造」的漏洞 —— 故须有测试断言防止回退。
 */
public interface CurrentPrincipal {

    /**
     * 取当前请求的可信上下文。
     *
     * @throws UnauthenticatedException 无令牌或令牌无效
     */
    cn.studykid.multiagent.core.tenant.TenantContext require();

    /** 未认证（401）。与「已认证但无权」（403）语义不同，不可混用。 */
    class UnauthenticatedException extends RuntimeException {
        public UnauthenticatedException(String message) {
            super(message);
        }
    }
}