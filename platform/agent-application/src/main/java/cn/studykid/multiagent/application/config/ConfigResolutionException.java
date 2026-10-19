package cn.studykid.multiagent.application.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 配置解析失败 —— <b>fail-closed</b> 的载体。
 *
 * <p>语义：配置缺失或非法时<b>拒绝</b>而非放行。捕获此异常的处理方
 * 必须让对应 Run 创建失败或返回明确错误，<b>不得</b>吞掉异常后继续用默认值执行。
 *
 * <p>对应约束：P0-D03 §4 红线 15、P0-D06 §8 DoD「配置缺失/非法时拒绝而非放行有测试」。
 */
public class ConfigResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String configKey;
    private final transient Object rawValue;

    public ConfigResolutionException(@NonNull String configKey,
                                     @Nullable Object rawValue,
                                     @NonNull String reason) {
        super("配置解析失败（fail-closed）: key=" + configKey
                + ", value=" + rawValue + ", 原因=" + reason);
        this.configKey = configKey;
        this.rawValue = rawValue;
    }

    public @NonNull String configKey() {
        return configKey;
    }

    public @Nullable Object rawValue() {
        return rawValue;
    }
}
