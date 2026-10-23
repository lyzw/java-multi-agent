package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;

/**
 * 发布 CAS 冲突（§6.3 第 3/4 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>这不是配置错误，也不是系统故障</b>：
 * 它表示「你读到的发布基线已经过时，另一方先发布成功了」。
 * 处置方式是<b>变基重试</b>（§6.3 第 4 条），而不是报错让人来查。
 *
 * <p>⚠️ <b>抛出它必然导致整事务回滚</b>：
 * 指针 CAS 失败时，本次发布的版本行与发布头记录必须一并消失，
 * 否则会留下「有发布记录、但当前指针没动」的半成品当前发布（PUB-1）。
 */
public class ConfigPublishConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long expectedPublishSeq;

    /**
     * @param expectedPublishSeq 冲突时的期望基线（{@code null} 表示「期望尚未发布过」）
     * @param message            冲突说明
     */
    public ConfigPublishConflictException(Long expectedPublishSeq, @NonNull String message) {
        super(message);
        this.expectedPublishSeq = expectedPublishSeq;
    }

    /**
     * @return 冲突时的期望基线
     */
    public Long expectedPublishSeq() {
        return expectedPublishSeq;
    }
}
