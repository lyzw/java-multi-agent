package cn.studykid.rag.core.scope;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RLS 依赖度量探针（M4 准备期埋点）。
 *
 * <p><b>为什么存在</b>：RLS→应用层迁移的真出口是 {@code rlsDependent = 0}
 * （见改造计划「真出口」标注）。M4 按表策略逐批退役 RLS 时，每撤一批
 * {@code set_config('app.tenant_id', ...)} 站点就少一批；本类是这批站点的
 * <b>权威计数源</b>与<b>运行期信号</b>。
 *
 * <p><b>两层口径</b>：
 * <ol>
 *   <li><b>静态预期（EXPECTED）</b>：各批次「仍依赖 RLS 的 set_config 站点」数，
 *       由 {@code RlsDependencyMetricTest} 对照源码静态盘点强制校验
 *       （任何新增/遗漏都会让该守卫转红）。随 M4 逐批移除而递减，直到全 0。</li>
 *   <li><b>运行期计数（LIVE）</b>：仅中央绑定器 {@code RagSessionScope} 调用时递增，
 *       是影子期（M2）可观测的 live 信号；其余按仓库直连的 set_config 站点
 *       由静态盘点覆盖，不在此 live 计数内（避免误导性的局部数字）。</li>
 * </ol>
 *
 * <p>本类纯 JDK 依赖，无 Spring / JDBC 依赖，可安全置于 rag-core（受
 * {@code RagCorePurityTest} 约束）。
 */
public final class RlsDependencyProbe {

    /** M4 逐批退役的批次（与改造计划 §5.2 rag 批次 R1/R2/R3 + 中央绑定器对齐）。 */
    public enum Batch {
        /** R1 检索读面：rag_chunk / rag_chunk_embedding / rag_document / rag_source */
        R1,
        /** R2 管理面：rag_dictionary / rag_knowledge_point / rag_knowledge_prerequisite / rag_kp_alias / rag_edition / rag_reviewer_roster */
        R2,
        /** R3 摄取/索引/巡检：rag_ingest_job / rag_index_generation / rag_inspection_record / rag_answer_citation */
        R3,
        /** 中央绑定器 RagSessionScope（跨批，末批移除） */
        CENTRAL
    }

    /**
     * 各批次「仍依赖 RLS 的 set_config 站点」预期数（静态盘点，2026-11-13）。
     * 源头：rag 主代码可执行 set_config 调用 30 处 = R1(6) + R2(6) + R3(16) + CENTRAL(2)。
     * M4 每撤一批即在此递减，最终全部归 0 → rlsDependent=0。
     */
    public static final Map<Batch, Integer> EXPECTED = Map.of(
            Batch.R1, 6,
            Batch.R2, 6,
            Batch.R3, 16,
            Batch.CENTRAL, 2
    );

    private static final EnumMap<Batch, AtomicLong> LIVE = new EnumMap<>(Batch.class);

    static {
        for (Batch b : Batch.values()) {
            LIVE.put(b, new AtomicLong(0L));
        }
    }

    private RlsDependencyProbe() {
    }

    /** 记录一次中央绑定器的 set_config 调用（live 信号）。 */
    public static void record(Batch batch) {
        LIVE.get(batch).incrementAndGet();
    }

    public static long live(Batch batch) {
        return LIVE.get(batch).get();
    }

    /** 运行期中央绑定器调用总数（影子期观测用）。 */
    public static long liveTotal() {
        long sum = 0L;
        for (AtomicLong v : LIVE.values()) {
            sum += v.get();
        }
        return sum;
    }

    /** 静态预期总数（rlsDependent 的目标，应随 M4 递减至 0）。 */
    public static int expectedTotal() {
        int sum = 0;
        for (int v : EXPECTED.values()) {
            sum += v;
        }
        return sum;
    }

    /** 单行快照，供启动自检 / 日志输出。 */
    public static String snapshot() {
        return "rlsDependent[expected=" + expectedTotal()
                + " R1=" + EXPECTED.get(Batch.R1)
                + " R2=" + EXPECTED.get(Batch.R2)
                + " R3=" + EXPECTED.get(Batch.R3)
                + " CENTRAL=" + EXPECTED.get(Batch.CENTRAL)
                + " liveCentral=" + live(Batch.CENTRAL) + "]";
    }
}
