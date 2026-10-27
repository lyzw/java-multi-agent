package cn.studykid.rag.infrastructure;

/**
 * 本地占位端口的 fail-closed 闸门（评审 P0-6）。
 *
 * <p><b>为什么需要它</b>：G1/G3 的三个占位实现
 * （{@code LocalDeterministicDocumentParserPort} / {@code LocalDeterministicEmbeddingPort} /
 * {@code LocalLexicalEntailmentPort}）此前一旦被装配进 Spring 上下文就会<b>静默</b>工作：
 * 关键词启发式解析可能误判答案区（泄题）、bigram 哈希嵌入会串知识点、
 * 蕴含占位对存在性恒放行（幻觉引用与冲突检测实际关闭）。
 * 「注释里写了诚实边界」挡不住一次漫不经心的部署。
 *
 * <p><b>语义</b>：占位端口默认<b>不得</b>装配（{@code rag.local-ports.enabled} 缺省
 * {@code false}，bean 创建期直接抛异常 = 应用拒绝启动）；只有显式设为
 * {@code true}（环境变量 {@code RAG_LOCAL_PORTS_ENABLED=true}）才放行，
 * 且放行时各装配类会打 <b>ERROR 级横幅</b>——「显式、留痕、响亮」，
 * 不给静默进生产留任何路径。真实实现（Docling / BGE-M3 / 独立 NLI）接入后
 * 应替换对应 bean 而绕过本闸门（A-008 阻断解除前的过渡形态）。
 */
public final class LocalPortGate {

    private LocalPortGate() {
    }

    /**
     * 占位端口装配守卫：未显式启用时抛异常，拒绝 bean 创建（fail-closed）。
     *
     * @param enabled 是否已通过 {@code rag.local-ports.enabled=true} 显式启用
     * @param portDescription 端口描述（用于错误信息定位，如
     *                        {@code "EmbeddingPort → LocalDeterministicEmbeddingPort"}）
     * @throws IllegalStateException 未启用时必抛（消息含启用方式）
     */
    public static void requireEnabled(boolean enabled, String portDescription) {
        if (!enabled) {
            throw new IllegalStateException("""
                    [RAG fail-closed] 本地占位端口未获显式启用，拒绝装配：%s。

                    该实现为本地确定性占位（非 Docling / BGE-M3 / 独立 NLI），
                    ⚠️ 不得用于生产：会导致答案区误判泄题、知识点串扰、
                    幻觉引用与冲突检测失效（评审 P0-6 · RAG-D09 诚实边界）。

                    如仅为本地开发/链路验证，请显式设置
                      rag.local-ports.enabled=true（或环境变量 RAG_LOCAL_PORTS_ENABLED=true）
                    后重启；该操作会被启动横幅记录（ERROR 级），不会被静默忽略。"""
                    .formatted(portDescription));
        }
    }
}
