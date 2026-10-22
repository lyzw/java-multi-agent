package cn.studykid.rag.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.rag.application.grounding.GroundingService;
import cn.studykid.rag.application.ingest.IngestionPipeline;
import cn.studykid.rag.core.grounding.GroundingPolicy;
import cn.studykid.rag.infrastructure.grounding.LocalLexicalEntailmentPort;
import cn.studykid.rag.infrastructure.parsing.LocalDeterministicDocumentParserPort;
import cn.studykid.rag.ports.DocumentParserPort;
import cn.studykid.rag.ports.EntailmentPort;

/**
 * 本地占位端口装配（RAG-D09 R2-2 · G1/G3 垂直切片）。
 *
 * <p>把<b>占位实现</b>暴露为 Spring bean，使 G1（入库切分）
 * 与 G3（接地判定）的用例在<b>无外部模型/解析器依赖</b>下即可装配运行：
 * <pre>
 *   DocumentParserPort   → LocalDeterministicDocumentParserPort   （G1 占位，非 Docling）
 *   EntailmentPort       → LocalLexicalEntailmentPort             （G3 占位，非 NLI 模型）
 *   GroundingPolicy      → GroundingPolicy.productionDefault()
 *   IngestionPipeline    → new IngestionPipeline(parserPort)       （G1 用例）
 *   GroundingService     → new GroundingService(entailment, policy)（G3 用例）
 * </pre>
 *
 * <p><b>⚠️ 诚实边界</b>：本配置暴露的解析/蕴含实现均为<b>本地确定性占位</b>，
 * 仅用于打通 G1/G3 链路与本地开发，<b>不代表 G1/G3 已生产就绪</b>。
 * G1 需 Docling（版面感知解析）、G3 需独立 NLI/判别式，二者目前均受
 * <b>A-008 硬阻断</b>（「首期不做 RAG」）与缺失外部依赖约束。
 *
 * <p>本配置位于 {@code cn.studykid.rag.infrastructure}，会被 rag-bootstrap
 * （扫描该包）自动纳入；rag-worker 通过 {@code @Import} 显式引入，
 * 以便 Worker 上下文也能托管 G1/G3 切片（未来可扩展为完整入库执行器）。
 */
@Configuration
public class LocalRagPortsConfig {

    @Bean
    public DocumentParserPort documentParserPort() {
        return new LocalDeterministicDocumentParserPort();
    }

    @Bean
    public EntailmentPort entailmentPort() {
        return new LocalLexicalEntailmentPort();
    }

    @Bean
    public GroundingPolicy groundingPolicy() {
        return GroundingPolicy.productionDefault();
    }

    @Bean
    public IngestionPipeline ingestionPipeline(DocumentParserPort parserPort) {
        return new IngestionPipeline(parserPort);
    }

    @Bean
    public GroundingService groundingService(EntailmentPort entailmentPort,
                                             GroundingPolicy groundingPolicy) {
        return new GroundingService(entailmentPort, groundingPolicy);
    }
}
