package cn.studykid.rag.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * 本地占位端口装配（RAG-D09 R2-2 · G1/G3 垂直切片 · 评审 P0-6 fail-closed 化）。
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
 * <p><b>⚠️⚠️ fail-closed（评审 P0-6）</b>：本配置暴露的解析/蕴含实现均为<b>本地确定性占位</b>，
 * 仅用于打通 G1/G3 链路与本地开发，<b>不代表 G1/G3 已生产就绪</b>。
 * G1 需 Docling（版面感知解析）、G3 需独立 NLI/判别式，二者目前均受
 * <b>A-008 硬阻断</b>。因此占位 bean 默认<b>拒绝装配</b>——
 * 须显式设置 {@code rag.local-ports.enabled=true}
 * （环境变量 {@code RAG_LOCAL_PORTS_ENABLED=true}）才放行；
 * 未启用时任何对本配置 bean 的创建请求都会抛异常（应用拒绝启动），
 * 启用时会打 ERROR 级横幅留痕。详见 {@link LocalPortGate}。
 */
@Configuration
public class LocalRagPortsConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalRagPortsConfig.class);

    /**
     * 占位端口显式启用开关（缺省 {@code false} = fail-closed）。
     * 构造期定格；启用时在此打 ERROR 横幅，保证「占位在跑」这件事在日志里可见。
     */
    public LocalRagPortsConfig(
            @Value("${rag.local-ports.enabled:false}") boolean localPortsEnabled) {
        this.localPortsEnabled = localPortsEnabled;
        if (localPortsEnabled) {
            log.error("[RAG 占位端口已启用] DocumentParserPort/EntailmentPort 将由本地确定性占位"
                    + "实现（非 Docling/NLI）承载——仅限本地开发与链路验证，"
                    + "⚠️ 不得用于生产（泄题/幻觉风险，评审 P0-6）。");
        }
    }

    private final boolean localPortsEnabled;

    @Bean
    public DocumentParserPort documentParserPort() {
        LocalPortGate.requireEnabled(localPortsEnabled,
                "DocumentParserPort → LocalDeterministicDocumentParserPort");
        return new LocalDeterministicDocumentParserPort();
    }

    @Bean
    public EntailmentPort entailmentPort() {
        LocalPortGate.requireEnabled(localPortsEnabled,
                "EntailmentPort → LocalLexicalEntailmentPort");
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
