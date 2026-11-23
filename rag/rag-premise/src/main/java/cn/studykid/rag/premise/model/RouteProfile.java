package cn.studykid.rag.premise.model;

/**
 * 页级解析路由档位（RAG-D27 §4.1 / D28 P1-1）。
 *
 * <p><b>⚠️ 升级不可逆</b>：档位严格度有序——
 * {@code LIGHTWEIGHT < HEAVY < QUARANTINE}。一旦某页被判到更严格档位，
 * 不得再用轻量档位的结果覆盖（{@code RoutingParseBackend} 的合并逻辑承担此约束）。
 *
 * <p>⚠️ 顶层类型（不嵌套），规则来源见 {@code NestedTypeImportGuardTest}（D09 §6）。
 */
public enum RouteProfile {

    /** 干净页：文本层完整、无复杂块，走轻量文本通道（最快）。 */
    LIGHTWEIGHT,

    /** 复杂页：公式/表格/图像密集或置信不足，升级 VLM 后端（MinerU）。 */
    HEAVY,

    /** 疑似扫描件：文本层稀疏且图像占比高，隔离人工复核（D29 DEC-P1：不静默当文本 PDF）。 */
    QUARANTINE;

    /** @return 严格度序号（越大越严格；用于「升级不可逆」比较） */
    public int strictness() {
        return switch (this) {
            case LIGHTWEIGHT -> 0;
            case HEAVY -> 1;
            case QUARANTINE -> 2;
        };
    }

    /** @return 是否需要升级到 VLM 后端 */
    public boolean escalates() {
        return this == HEAVY;
    }

    /** @return 是否需要隔离（不进入常规入库链） */
    public boolean quarantines() {
        return this == QUARANTINE;
    }
}
