package cn.studykid.rag.premise.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PageRoute;
import cn.studykid.rag.premise.model.PageSignals;
import cn.studykid.rag.premise.model.ParseTrace;
import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.model.RouteProfile;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * 页级分级解析后端（D27 §4.1 / D28 P1-1+P1-2）：包装轻量与升级两个后端。
 *
 * <p><b>⚠️ 工序</b>：
 * <ol>
 *   <li>先用 primary（轻量，如 Docling）解析整册——这是「便宜路径」；</li>
 *   <li>从产物按页推导 {@link PageSignals}，交给 {@link PageRouter} 逐页决策；</li>
 *   <li>有 {@link RouteProfile#HEAVY} 页时，才调 escalation（如 MinerU）解析整册；</li>
 *   <li><b>逐页合并</b>：升级页只取 escalation 的块，干净页保留 primary 的块；</li>
 *   <li>{@link RouteProfile#QUARANTINE} 页抛 {@link PageQuarantineException}（fail-closed）。</li>
 * </ol>
 *
 * <p><b>⚠️ 升级不可逆</b>：合并是「<b>按页替换</b>」而非「取两者较优」——
 * 升级页即使 escalation 结果更差（质量分更低/块更少），也<b>不得</b>回退到 primary 结果。
 * 回退会让「判为复杂页」这一步失去意义，并让同一页的解析来源不可追溯。
 *
 * <p><b>⚠️ 混合后端的 doclingJsonRef 必须清空</b>：
 * {@code HybridChunkerClient} 会复用整册 Docling JSON 做切分；一旦某些页来自 MinerU，
 * 那份 JSON 已不代表最终文档，继续传入会切出错位块。设置为 null 让桥 fail-closed。
 *
 * <p><b>⚠️ fail-closed</b>：escalation 抛异常时不吞、不降级，直接传播；
 * 这与 {@link DocumentParseBackend} SPI 的纪律一致。
 */
public final class RoutingParseBackend implements DocumentParseBackend {

    private final DocumentParseBackend primary;
    private final DocumentParseBackend escalation;
    private final PageRouter router;

    public RoutingParseBackend(@NonNull DocumentParseBackend primary,
                               @NonNull DocumentParseBackend escalation,
                               @NonNull PageRouter router) {
        if (primary == null || escalation == null || router == null) {
            throw new IllegalArgumentException("primary / escalation / router 均不能为空");
        }
        this.primary = primary;
        this.escalation = escalation;
        this.router = router;
    }

    @Override
    public @NonNull String backendId() {
        return "routing(" + primary.backendId() + "+" + escalation.backendId() + ")";
    }

    @Override
    public @NonNull String backendVersion() {
        return primary.backendVersion() + "/" + escalation.backendVersion();
    }

    @Override
    public @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        long started = System.currentTimeMillis();

        // ① 轻量通道先跑整册（两种档位共用同一份输入）
        PremiseDocument primaryDoc = primary.parse(documentId, content);

        // ② 按页推导信号（含「没有任何块」的空页——扫描件判定的关键信号）
        Map<Integer, List<PremiseBlock>> primaryByPage = groupByPage(primaryDoc.blocks());
        int maxPage = Math.max(primaryDoc.pageCount(),
                primaryByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        List<PageSignals> signals = new ArrayList<>(maxPage);
        for (int page = 1; page <= maxPage; page++) {
            signals.add(PageSignals.of(page,
                    primaryByPage.getOrDefault(page, List.of())));
        }

        // ③ 路由决策
        List<PageRoute> routes = router.route(signals);
        List<Integer> quarantined = new ArrayList<>();
        List<Integer> escalated = new ArrayList<>();
        for (PageRoute route : routes) {
            if (route.profile() == RouteProfile.QUARANTINE) {
                quarantined.add(route.pageNumber());
            } else if (route.profile() == RouteProfile.HEAVY) {
                escalated.add(route.pageNumber());
            }
        }

        // ④ 隔离页 fail-closed：不把扫描件当文本 PDF 解析出残缺块。
        if (!quarantined.isEmpty()) {
            throw new PageQuarantineException(quarantined);
        }

        // ⑤ 升级页才调贵通道；无升级页时 escalation 完全不调用。
        Map<Integer, List<PremiseBlock>> mergedByPage = new LinkedHashMap<>(primaryByPage);
        if (!escalated.isEmpty()) {
            PremiseDocument heavyDoc = escalation.parse(documentId, content);
            Map<Integer, List<PremiseBlock>> heavyByPage = groupByPage(heavyDoc.blocks());
            for (int page : escalated) {
                // ⚠️ 不可逆：无条件替换，不比较质量分、不回退 primary。
                mergedByPage.put(page,
                        heavyByPage.getOrDefault(page, List.of()));
            }
        }

        List<PremiseBlock> merged = new ArrayList<>();
        for (int page = 1; page <= maxPage; page++) {
            merged.addAll(mergedByPage.getOrDefault(page, List.of()));
        }

        // ⑥ 留痕：backendId 必须体现真实路由，不能沿用 primary（否则日志说 docling、实际混了 mineru）
        ParseTrace base = primaryDoc.trace();
        List<String> warnings = new ArrayList<>(base.warnings());
        for (PageRoute route : routes) {
            warnings.add("route: " + route.traceLine());
        }
        double minQuality = routes.stream()
                .mapToDouble(PageRoute::qualityScore)
                .min().orElse(Double.NaN);
        String reason = "clean=" + (routes.size() - escalated.size())
                + ",escalated=" + escalated.size()
                + ",quarantined=" + quarantined.size();
        if (!escalated.isEmpty()) {
            warnings.add("mixed backend: doclingJsonRef cleared（部分页来自 "
                    + escalation.backendId() + "，整册 Docling JSON 已失效）");
        }
        ParseTrace trace = new ParseTrace(
                backendId(),
                backendVersion(),
                maxPage,
                base.segmented(),
                base.segments(),
                warnings,
                System.currentTimeMillis() - started,
                reason,
                minQuality,
                routes);

        // ⚠️ 混合后端时清空 doclingJsonRef（见类注释）
        String doclingJsonRef = escalated.isEmpty() ? primaryDoc.doclingJsonRef() : null;
        return new PremiseDocument(documentId, merged, trace, doclingJsonRef);
    }

    /** @return 轻量后端（装配自检 / 日志） */
    public @NonNull DocumentParseBackend primary() {
        return primary;
    }

    /** @return 升级后端（装配自检 / 日志） */
    public @NonNull DocumentParseBackend escalation() {
        return escalation;
    }

    /** @return 路由规则（含阈值，自检用） */
    public @NonNull PageRouter router() {
        return router;
    }

    private static @NonNull Map<Integer, List<PremiseBlock>> groupByPage(
            @NonNull List<PremiseBlock> blocks) {
        Map<Integer, List<PremiseBlock>> byPage = new LinkedHashMap<>();
        for (PremiseBlock block : blocks) {
            byPage.computeIfAbsent(block.pageNumber(), k -> new ArrayList<>()).add(block);
        }
        return byPage;
    }
}
