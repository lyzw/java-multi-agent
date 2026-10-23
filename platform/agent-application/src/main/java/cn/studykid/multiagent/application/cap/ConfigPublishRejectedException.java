package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 配置发布被拒（配置期门禁 · W-PERM-P5）。
 *
 * <p>⚠️ <b>为什么发布拒绝要有独立异常</b>：
 * 「配置非法」与「系统故障」在处置上完全不同 ——
 * 前者要<b>修配置</b>，后者要<b>查服务</b>。
 * 若两者都用同一个运行时异常表达，运维会拿着「发布失败」的告警去查数据库与连接池，
 * 而真实原因只是某个 Tool 的 risk_level 写错了。
 * 故本异常携带 {@link ReasonCode}，让失败原因**直接可读**。
 *
 * <p>⚠️ <b>本异常只在「配置期门禁」阶段抛出</b>：
 * CAS 失败（并发基线已变）是<b>可重试</b>的竞争，不属于配置问题，
 * 由发布服务内部变基重试，不外泄为本异常。
 */
public class ConfigPublishRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ReasonCode reasonCode;
    private final String field;

    /**
     * @param reasonCode 原因码（§5.7）
     * @param field      违规字段（面向配置作者）
     * @param message    违规说明
     */
    public ConfigPublishRejectedException(@NonNull ReasonCode reasonCode,
                                          @NonNull String field,
                                          @NonNull String message) {
        super(field + ": " + message);
        this.reasonCode = reasonCode;
        this.field = field;
    }

    /**
     * @return 原因码（§5.7）
     */
    @NonNull
    public ReasonCode reasonCode() {
        return reasonCode;
    }

    /**
     * @return 违规字段
     */
    @NonNull
    public String field() {
        return field;
    }
}
