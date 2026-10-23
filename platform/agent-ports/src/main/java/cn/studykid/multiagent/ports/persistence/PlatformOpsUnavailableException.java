package cn.studykid.multiagent.ports.persistence;

import org.jspecify.annotations.NonNull;

/**
 * 平台运维角色（BYPASSRLS）未接入，PLATFORM 作用域发布不可用。
 *
 * <p>⚠️ <b>为什么是独立异常类型，而不是复用「发布被拒绝」</b>：
 * 「配置非法被门禁拒绝」与「平台运维凭据未接入」是<b>两件完全不同的事</b>：
 * <ul>
 *   <li>前者是<b>业务结论</b>——这份配置不该发布，重试无意义；</li>
 *   <li>后者是<b>运行期前置条件缺失</b>——配置本身完全合法，
 *       只是没有能写 PLATFORM 行的数据库角色。</li>
 * </ul>
 * 混为一谈会让运维把「没配凭据」当成「配置写错了」，
 * 排查方向从一开始就跑偏；而复用「被拒绝」还会让上层误以为
 * 应当以 4xx 回应一个纯属运维配置缺失的故障。
 *
 * <p>⚠️ <b>本异常代表 fail-closed，绝不代表「降级放行」</b>：
 * 平台运维角色不可用时，PLATFORM 作用域发布<b>一律失败</b>，
 * <b>不得</b>回落到应用角色（RLS 会拒写，但报错点落在 RLS 策略上，
 * 离「这个环境没配平台运维凭据」这一真因极远）。
 *
 * <p>⚠️ <b>与 RLS 拒写的区别</b>：
 * 应用角色写 PLATFORM 行会被 PostgreSQL 的 RLS 策略拒绝（{@code new row violates
 * row-level security policy}）。那是「角色不对」的证据；
 * 本异常是「角色根本没接入」的可读结论。两者都拒绝，但后者的现场可直接指向配置项。
 */
public class PlatformOpsUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String missingConfiguration;

    public PlatformOpsUnavailableException(@NonNull String missingConfiguration,
                                           @NonNull String message) {
        super(message);
        this.missingConfiguration = missingConfiguration;
    }

    /**
     * @return 缺失的配置项路径（如 {@code agent.platform-ops.datasource.url}），
     *         供运维直接定位
     */
    public @NonNull String missingConfiguration() {
        return missingConfiguration;
    }
}
