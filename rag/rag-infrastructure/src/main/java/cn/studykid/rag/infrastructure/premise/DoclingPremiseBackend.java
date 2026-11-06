package cn.studykid.rag.infrastructure.premise;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.infrastructure.parsing.DoclingDocumentParserPort;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.ParsedDocument;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * Docling 解析后端（RAG-D26 阶段 1.1）——<b>包装既有实现，不重写</b>。
 *
 * <p><b>⚠️ 为什么放在 rag-infrastructure 而不是 rag-premise</b>：
 * 既有 {@link DoclingDocumentParserPort} 住在 infrastructure 里，
 * 它已经解决了大量实测问题——分段提交（整册 710 秒不可行，20 页/段 36~54 秒）、
 * 强制 HTTP/1.1（h2c 升级会被 docling-serve 400）、页码缺失即报错
 * （不瞎填默认值，否则「可回取原文」门禁失效）、fail-closed 不回落占位——
 * 这些是验证过的工程资产，重写等于丢掉。而 rag-premise 不依赖
 * rag-infrastructure（分层约束），因此包装类必须放在本侧：
 * 依赖方向 infrastructure → premise，与既有分层一致。
 *
 * <p><b>适配职责（仅 SPI 形状，无语义改动）</b>：
 * <ol>
 *   <li>调用既有端口拿 {@link ParsedDocument}（既有契约零感知）；</li>
 *   <li>{@link PremiseBlock#fromLayoutBlocks} 恒等包装成
 *       {@link PremiseDocument}（不复制字段，杜绝双词汇表漂移）；</li>
 *   <li>留痕取既有端口的 {@link DoclingDocumentParserPort#PARSER_VERSION}；</li>
 *   <li>页数取自块的最大页码（既有端口不回传总页数；0 页文档记 0，
 *       与 DDL 的 {@code page_count >= 0} 对齐）。</li>
 * </ol>
 *
 * <p><b>⚠️ fail-closed 继承</b>：既有端口在未配置 / 服务不可用时抛
 * {@code DoclingUnavailableException}，本类<b>不捕获不转换</b>——
 * 异常类型穿透到编排器，与既有失败路径完全一致
 * （绝不返回空文档或降级到占位解析器）。
 */
public class DoclingPremiseBackend implements DocumentParseBackend {

    private final DocumentParserPort delegate;
    private final String backendVersion;

    /**
     * @param delegate 既有 Docling 端口（生产为 {@link DoclingDocumentParserPort}；
     *                 测试可为任意实现）
     */
    public DoclingPremiseBackend(@NonNull DocumentParserPort delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        this.delegate = delegate;
        // 版本号只在真实 Docling 端口下有权威来源（PARSER_VERSION 常量）；
        // 其他实现（测试桩）视为「版本未知」——ParseTrace 契约：未知为空串，不得为 null，
        // 也不得拿 lambda 类名冒充版本号（会污染 parser_version 留痕）。
        this.backendVersion = delegate instanceof DoclingDocumentParserPort docling
                ? docling.PARSER_VERSION
                : "";
    }

    @Override
    public @NonNull String backendId() {
        return "docling-serve";
    }

    @Override
    public @NonNull String backendVersion() {
        return backendVersion;
    }

    @Override
    public @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        // ⚠️ 走 parseWithRaw：一次往返同时产出块序列与可复用的原始 DoclingDocument
        //    （分块桥的数据流约束①——不得二次解析，见类注释与 §2.4）。
        DoclingDocumentParserPort.ParseWithRaw result =
                delegate instanceof DoclingDocumentParserPort docling
                        ? docling.parseWithRaw(documentId, content)
                        : new DoclingDocumentParserPort.ParseWithRaw(
                                documentId, delegate.parse(documentId, content).blocks(),
                                null, false);
        ParsedDocument parsed = result.parsed();
        // ⚠️ raw 只在「整册单响应」时可信（分段合并后的块序列与整册 DoclingDocument
        //    不再一一对应，item_refs 会错位）——此处只透传，由 HybridChunkerClient
        //    在 doclingJsonRef 为空/不可信时 fail-closed。
        String rawJson = result.wholeDocument() ? result.rawJson() : null;
        ParseTrace trace = new ParseTrace(
                backendId(),
                backendVersion,
                maxPageOf(parsed),
                !result.wholeDocument(),
                1,
                rawJson == null
                        ? List.of("chunk-bridge 数据流前提缺失：doclingJsonRef 不可用"
                                + "（分段解析或非 Docling 委托），HYBRID_CHUNKER 将 fail-closed")
                        : List.of());
        return new PremiseDocument(
                documentId,
                PremiseBlock.fromLayoutBlocks(parsed.blocks()),
                trace,
                rawJson);
    }

    private static int maxPageOf(ParsedDocument parsed) {
        return parsed.blocks().stream()
                .mapToInt(b -> b.pageNumber())
                .max()
                .orElse(0);
    }
}
