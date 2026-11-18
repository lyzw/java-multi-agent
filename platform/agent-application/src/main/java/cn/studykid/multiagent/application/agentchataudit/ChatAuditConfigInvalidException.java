package cn.studykid.multiagent.application.agentchataudit;

import org.jspecify.annotations.NonNull;

/**
 * 对话审计配置校验失败（前端写入时 → 400）。
 *
 * <p>与 {@code ConfigResolutionException}（读取期 fail-closed）正交：
 * 本异常发生在<b>写入期</b>，消息可安全回显给平台管理员（不含密钥）。
 */
public class ChatAuditConfigInvalidException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ChatAuditConfigInvalidException(@NonNull String message) {
        super(message);
    }
}
