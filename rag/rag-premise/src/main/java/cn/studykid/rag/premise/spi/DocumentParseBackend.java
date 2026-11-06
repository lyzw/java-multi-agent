package cn.studykid.rag.premise.spi;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PremiseDocument;

/**
 * 解析后端 SPI（RAG-D26 §2.2）。
 *
 * <p><b>⚠️ 实现类必须 fail-closed</b>：后端不可用 / 未配置时抛异常，
 * <b>绝不</b>返回空文档、<b>绝不</b>静默降级到另一个后端。
 * 理由与既有 {@code DoclingDocumentParserPort} 同源：
 * 占位解析的答案区启发式可能泄题，「看起来成功实则泄题」
 * 比明确失败危险一个数量级（评审 P0-6 的结论）。
 *
 * <p><b>与既有 {@code DocumentParserPort} 的关系</b>：
 * 本 SPI 是 RAG-D26 引入的<b>多后端</b>抽象；既有端口保留为「兼容路径」——
 * 装配方在过渡期可先用 {@code DocumentParserPort} 的实现包装成本 SPI
 * （见 {@code adapter/docling}），迁移完成后再直连各后端。
 *
 * <p>实现类约束（与既有工程约定一致）：
 * <ul>
 *   <li><b>确定性</b>：同一输入同一配置 → 同一输出（chunk_id 依赖可复现）；</li>
 *   <li><b>无状态</b>：不持有任何与单次解析相关的可变状态；</li>
 *   <li><b>线程安全</b>：实例会被单例装配共享；</li>
 *   <li><b>顶层类型规则</b>：本 SPI 的词汇表一律顶层声明，
 *       不得引入嵌套类型（RAG-D09 §6）。</li>
 * </ul>
 */
public interface DocumentParseBackend {

    /**
     * @return 后端标识（写入 {@link cn.studykid.rag.premise.model.ParseTrace}），
     *         如 {@code docling-serve} / {@code mineru} / {@code pp-structurev3}
     */
    @NonNull String backendId();

    /**
     * @return 后端版本（未知返回空串，不得返回 null）
     */
    @NonNull String backendVersion();

    /**
     * 解析文档为归一化块序列。
     *
     * <p>⚠️ 页码口径：统一为 <b>1 起</b>物理页码（PDF 页序号）。
     * 后端原生从 0 起数的（如 MinerU 的 {@code page_idx}）必须在适配器内 +1 换算，
     * 不得把 0 起页码透传给下游——DDL 约束 {@code rag_chunk.page_number >= 1}
     * 会直接拒绝，且「从 0 起的页码」会系统性错位引用。
     *
     * @param documentId 文档 ID
     * @param content    文档内容（PDF 字节流或文本）
     * @return 归一化文档
     * @throws RuntimeException 后端不可用 / 解析失败（实现类自选异常类型，必须 fail-closed）
     */
    @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content);
}
