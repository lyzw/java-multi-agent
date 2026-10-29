package cn.studykid.rag.infrastructure.ops;

/**
 * 平台运维通道（{@code rag_ops}）未接入时抛出的异常。
 *
 * <p><b>为什么必须是独立异常</b>：这条通道缺失时，正确行为是
 * <b>拒绝服务</b>（503），而不是回落到应用角色 {@code rag_app}——
 * 回落的结果不是「功能稍差」，而是全局桶写不进去却报「成功」
 * （RLS 对跨桶写入是静默 0 行），或者把本该 403 的请求变成 500。
 * 把它和别的运行时异常混在一起，运维就只能靠猜来区分这两种世界。
 *
 * <p>{@link #missingConfiguration()} 让告警直接指向要补的配置键，
 * 而不是让人去翻堆栈。
 */
public class RagOpsUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String missingConfiguration;

    public RagOpsUnavailableException(String missingConfiguration, String message) {
        super(message);
        this.missingConfiguration = missingConfiguration;
    }

    /**
     * 需要补齐的配置键（如 {@code rag.ops.datasource.url}）；
     * 通道已配置但角色不合格时为 {@code rag.ops.role}。
     */
    public String missingConfiguration() {
        return missingConfiguration;
    }
}
