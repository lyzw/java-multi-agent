package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 参数策略配置缺陷（§4.3「未知 op → 配置期拒」的运行期表现）。
 *
 * <p>⚠️ <b>这是「配置非法」而非「调用被拒」</b>：它表示数据库里的参数策略本身写错了
 * （未知 op、结构非法、阈值非数值），不是「本次调用参数不满足策略」。
 * 二者的处置不同：
 * <ul>
 *   <li>配置缺陷 → 需要<b>修配置</b>，调用方转 {@code PARAM_POLICY_FAIL} 拒绝本次调用；</li>
 *   <li>调用不满足 → 属正常业务拒绝，同一个原因码。</li>
 * </ul>
 * 但对外<b>都表现为拒绝</b>（fail-closed）—— 绝不因为「看不懂策略」而放行。
 */
public class ParamPolicyConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ParamPolicyConfigException(@NonNull String message) {
        super(message);
    }

    public ParamPolicyConfigException(@NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
