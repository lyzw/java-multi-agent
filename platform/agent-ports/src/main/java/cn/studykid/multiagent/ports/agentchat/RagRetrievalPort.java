package cn.studykid.multiagent.ports.agentchat;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * RAG 检索端口（platform → rag 独立服务，跨进程 HTTP）。
 *
 * <p><b>为什么是端口</b>：platform 与 rag 是两个独立 Maven reactor / 进程，
 * platform 侧此前没有任何 HTTP 客户端指向 rag（已全仓核实）。本端口把
 * 「调 rag」抽象为领域可读的检索结果，HTTP / 认证 / 超时细节全在 infrastructure 适配器。
 *
 * <p><b>认证</b>：适配器透传调用方已验签的 Bearer JWT（租户链用租户令牌，
 * 平台链用平台令牌）+ {@code X-Tenant-Id}；rag 的 {@code RagJwtAuthFilter} 验签，
 * 租户域强制 {@code tenant_id claim == X-Tenant-Id}，平台域允许跨租户带该头。
 *
 * <p><b>fail-closed</b>：rag 不可用 / 超时 / 400 时抛 {@link RagUnavailableException}，
 * 编排器据此发组件级 {@code info}，<b>绝不</b>伪造命中或静默当零命中。
 */
public interface RagRetrievalPort {

    /**
     * 检索。
     *
     * @param tenantId    目标租户（UUID 字符串，已由控制器校验）
     * @param bearerToken 已验签的 Bearer 令牌（不含 "Bearer " 前缀）
     * @param query       检索 query
     * @param topK        召回条数（1..50）
     * @return 检索结果（可能零命中，零命中是合法业务结果）
     */
    @NonNull RagResult retrieve(@NonNull String tenantId,
                                @NonNull String bearerToken,
                                @NonNull String query,
                                int topK);

    /** 检索结果（七字段命中 + 版本/计数元信息，均只含展示白名单字段）。 */
    record RagResult(@NonNull List<AgentChatEvent.Hit> hits,
                     boolean versionMismatchDetected,
                     boolean answerContentSuppressed,
                     @Nullable String primaryEdition,
                     int returnedCount) {
    }

    /** rag 服务不可用 / 参数不合法 / 鉴权失败（统一归一化，不泄漏密钥与正文）。 */
    class RagUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RagUnavailableException(@NonNull String message) {
            super(message);
        }

        public RagUnavailableException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
