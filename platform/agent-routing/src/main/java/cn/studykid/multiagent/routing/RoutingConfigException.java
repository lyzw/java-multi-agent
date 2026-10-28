package cn.studykid.multiagent.routing;

import org.jspecify.annotations.NonNull;

/**
 * 配置期（发布期）拒绝：策略文本不符合封闭文法或超出硬限制。
 *
 * <p>⚠️ <b>只在配置期抛，运行期永不抛</b>：
 * 运行期遇到求值异常走 {@code onError}（SKIP/REJECT）并留稳定码；
 * 若运行期还能抛「文法错误」，说明这份策略<b>根本没经过发布校验</b> ——
 * 那本身就是要报的缺陷，而不是一个业务结果。
 */
public class RoutingConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 出问题的位置（规则序号 / 谓词路径），便于配置者一眼定位。 */
    private final String where;

    public RoutingConfigException(@NonNull String where, @NonNull String message) {
        super("[" + where + "] " + message);
        this.where = where;
    }

    public RoutingConfigException(@NonNull String where, @NonNull String message,
                                  @NonNull Throwable cause) {
        super("[" + where + "] " + message, cause);
        this.where = where;
    }

    public @NonNull String where() {
        return where;
    }
}
