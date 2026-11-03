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
import cn.studykid.rag.infrastructure.parsing.DoclingDocumentParserPort;
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

    /**
     * 解析端口装配（V1.0.2 S2-1）。
     *
     * <p><b>选择逻辑</b>：
     * <ul>
     *   <li>配置了 {@code rag.docling.base-url} → <b>Docling 适配器</b>（生产路径）；</li>
     *   <li>未配置 → 占位解析器，但受 {@link LocalPortGate} fail-closed 约束
     *       （须显式 {@code rag.local-ports.enabled=true} 才放行）。</li>
     * </ul>
     *
     * <p><b>⚠️ 两条路径都不会「自动回落」</b>：配了 Docling 地址后若服务不可用，
     * Docling 适配器抛 {@link DoclingDocumentParserPort.DoclingUnavailableException}，
     * job 置 FAILED——<b>绝不</b>悄悄换回占位解析器。
     * 理由见该类的类注释：占位解析器的答案区启发式可能泄题，
     * 「看起来成功实则泄题」比「明确失败」危险得多。
     */
    @Bean
    public DocumentParserPort documentParserPort(
            @Value("${rag.docling.base-url:}") String doclingBaseUrl,
            @Value("${rag.docling.timeout-seconds:900}") int doclingTimeoutSeconds,
            @Value("${rag.docling.pages-per-segment:20}") int pagesPerSegment,
            @Value("${rag.docling.max-segments:60}") int maxSegments) {
        if (doclingBaseUrl != null && !doclingBaseUrl.isBlank()) {
            // ⚠️ 实测（沪教版六年级下册 148 页）：整册同步解析需 710 秒，
            //    故默认按 20 页/段提交（每段 36~54 秒），避免超时与打满服务。
            log.info("[RAG] DocumentParserPort → Docling（base-url 已配置；"
                    + "分段解析，每段 {} 页，最多 {} 段，单请求超时 {}s）",
                    pagesPerSegment, maxSegments, doclingTimeoutSeconds);
            return new DoclingDocumentParserPort(doclingBaseUrl, doclingTimeoutSeconds,
                    pagesPerSegment, maxSegments);
        }
        LocalPortGate.requireEnabled(localPortsEnabled,
                "DocumentParserPort → LocalDeterministicDocumentParserPort");
        return new LocalDeterministicDocumentParserPort();
    }

    /**
     * 无参重载（向后兼容既有测试与切片上下文）。
     *
     * <p>⚠️ 语义等同于「未配置 Docling 端点」：只走占位解析器并受 fail-closed 闸门约束。
     * 生产装配由 Spring 注入 {@code rag.docling.base-url}，走带参版本。
     *
     * @return 占位解析器
     */
    public DocumentParserPort documentParserPort() {
        // ⚠️ 未配置 Docling → 走占位解析器（受 fail-closed 闸门约束）。
        //    分段参数在此不适用（占位实现没有远程调用）。
        return documentParserPort("", 900,
                cn.studykid.rag.infrastructure.parsing.DoclingDocumentParserPort
                        .DEFAULT_PAGES_PER_SEGMENT,
                cn.studykid.rag.infrastructure.parsing.DoclingDocumentParserPort
                        .DEFAULT_MAX_SEGMENTS);
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
