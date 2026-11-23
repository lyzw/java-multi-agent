package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 解析工序留痕（RAG-D26 §1.3 / §2.5）。
 *
 * <p><b>⚠️ 为什么解析留痕是一等类型而不是日志</b>：
 * {@code parser_version} 决定「这批语料是用哪版解析器切的」，换版后需要重灌，
 * 而重灌的依据就是这个字段（既有 {@code DoclingDocumentParserPort.PARSER_VERSION}
 * 的注释同源）。后端可切换后，仅一个版本号不够——还需要
 * <b>后端名 + 后端版本 + 分段方式 + 告警</b>才能完整解释一次解析。
 *
 * <p><b>与 DDL 的对应</b>：{@code backendId + backendVersion} 共同构成
 * {@code rag_document.parser_version} 的语义（写入格式由装配方决定，
 * 本类型只负责承载，不做字符串拼装）。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code NestedTypeImportGuardTest}。
 *
 * @param backendId      后端标识（如 {@code docling-serve} / {@code mineru} / {@code pp-structurev3}；
 *                       兼容路径为 {@code untracked}）
 * @param backendVersion 后端版本（未知为空串，<b>不得</b>为 null）
 * @param pageCount      解析出的物理页数（未知为 0）
 * @param segmented      是否启用分段提交（大册分段是 Docling 路径的实测约束）
 * @param segments       实际分段数（不分段为 1）
 * @param warnings       归一化阶段产生的非致命告警（如「第 12 页缺失页码，按顺序回填」）
 * @param elapsedMs      解析耗时（毫秒；-1 表示未知）
 * @param routeReason    页级路由汇总结论（D28 §2.2；无路由为空串）
 * @param qualityScore   轻量通道解析质量分的整册代表值（最低页；未评分为 NaN）
 * @param pageRoutes     逐页路由决策（D28 P1-1/P1-2 可观测性；未路由为空列表）
 */
public record ParseTrace(
        @NonNull String backendId,
        @NonNull String backendVersion,
        int pageCount,
        boolean segmented,
        int segments,
        @NonNull List<String> warnings,
        long elapsedMs,
        @NonNull String routeReason,
        double qualityScore,
        @NonNull List<PageRoute> pageRoutes) {

    public ParseTrace {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId 不能为空");
        }
        backendVersion = backendVersion == null ? "" : backendVersion;
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount 必须 >= 0");
        }
        if (segments < 1) {
            throw new IllegalArgumentException("segments 必须 >= 1");
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        routeReason = routeReason == null ? "" : routeReason;
        pageRoutes = pageRoutes == null ? List.of() : List.copyOf(pageRoutes);
    }

    /**
     * 六参兼容构造（旧调用点：Docling/Mineru 后端、测试夹具）。
     *
     * <p>⚠️ 新字段取「未设置」语义，不伪造数据：耗时 -1、路由空串、质量分 NaN、路由明细空表。
     */
    public ParseTrace(@NonNull String backendId, @NonNull String backendVersion,
                      int pageCount, boolean segmented, int segments,
                      @NonNull List<String> warnings) {
        this(backendId, backendVersion, pageCount, segmented, segments, warnings,
                -1L, "", Double.NaN, List.of());
    }

    /**
     * 兼容路径的留痕：解析仍走既有 {@code DocumentParserPort}，
     * 本模块只做包装、不掌握后端细节。
     *
     * @return 未跟踪留痕
     */
    public static @NonNull ParseTrace untracked() {
        return new ParseTrace("untracked", "", 0, false, 1, List.of());
    }

    /**
     * 追加告警（保持其余字段不变）。
     *
     * @param more 追加的告警
     * @return 新实例
     */
    public @NonNull ParseTrace withWarnings(@NonNull List<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        var merged = new java.util.ArrayList<String>(warnings);
        merged.addAll(more);
        // ⚠️ 逐字段带过：compat 构造器会静默清空新字段（项目铁律），此处必须走全参构造。
        return new ParseTrace(backendId, backendVersion, pageCount, segmented, segments,
                merged, elapsedMs, routeReason, qualityScore, pageRoutes);
    }

    /**
     * 追加路由留痕（保持其余字段不变）。
     *
     * @param reason      路由汇总结论
     * @param score       整册代表质量分
     * @param routes      逐页路由决策
     * @return 新实例
     */
    public @NonNull ParseTrace withRouting(@NonNull String reason, double score,
                                           @NonNull List<PageRoute> routes) {
        return new ParseTrace(backendId, backendVersion, pageCount, segmented, segments,
                warnings, elapsedMs, reason == null ? "" : reason, score,
                routes == null ? List.of() : List.copyOf(routes));
    }

    /**
     * 记录解析耗时（保持其余字段不变）。
     *
     * @param millis 耗时（毫秒）
     * @return 新实例
     */
    public @NonNull ParseTrace withElapsedMs(long millis) {
        return new ParseTrace(backendId, backendVersion, pageCount, segmented, segments,
                warnings, millis, routeReason, qualityScore, pageRoutes);
    }
}
