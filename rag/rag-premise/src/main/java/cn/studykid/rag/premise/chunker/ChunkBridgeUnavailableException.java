package cn.studykid.rag.premise.chunker;

/**
 * 分块桥不可用（{@code HybridChunkerClient} 的 fail-closed 异常，
 * RAG-D26 阶段 2.2）。
 *
 * <p><b>⚠️ 顶层类型而非客户端的嵌套类</b>：premise 层「类型顶层化」规则
 * （{@code PremiseTopLevelTypeGuardTest} / RAG-D09 §6）。
 *
 * <p><b>语义</b>：桥不可达 / 响应异常 / 产出 0 chunk / 数据流前提缺失
 * （doclingJsonRef 为空）——job 置 FAILED，<b>绝不</b>回退到
 * HEADING_HIERARCHY（策略是留痕事实，静默替换会让检索表现不可比）。
 */
public class ChunkBridgeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChunkBridgeUnavailableException(String message) {
        super(message);
    }

    public ChunkBridgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
