package cn.studykid.rag.core.ingest;

import java.util.Arrays;
import java.util.List;

/**
 * 摄取任务状态（RAG-V1.0.2 §3.2 Job 层）。
 *
 * <p><b>⚠️ 与数据库枚举 {@code rag.rag_ingest_status} 严格一一对应，不得增删值</b>：
 * {@code PENDING / PARSING / CHUNKING / EMBEDDING / INDEXING / SUCCEEDED / FAILED / REJECTED}。
 *
 * <p>方案 v1.0 曾计划用 {@code PENDING → RUNNING → DONE} 的粗粒度模型，但 DDL 枚举
 * 并无 RUNNING/DONE（改 PG ENUM 成本高且有存量风险），故 v1.0.2 起统一沿用细粒度枚举：
 * 「运行中」= PARSING/CHUNKING/EMBEDDING/INDEXING 的统称，「完成」= SUCCEEDED。
 * 阶段内的百分比进度由 {@code stage} 列承载，与本枚举正交。
 */
public enum IngestJobStatus {

    /** 已建单，等待 worker 领取。 */
    PENDING,

    /** 正在版面解析（{@code DocumentParserPort}）。 */
    PARSING,

    /** 正在清洗与结构化切分（rag-core 移植规则 + IngestionPipeline）。 */
    CHUNKING,

    /** 正在把切分结果落库并补嵌入（{@code IngestJobOrchestrator} 落库段）。 */
    EMBEDDING,

    /** 正在写索引（{@code ChunkIndexer}）。 */
    INDEXING,

    /** 全链路成功。 */
    SUCCEEDED,

    /** 处理失败（{@code error_detail} 必填）。 */
    FAILED,

    /**
     * 已取消。
     *
     * <p><b>⚠️ 本值有两类来源，靠 {@code error_detail} 区分</b>（方案 §7.1 / N2）：
     * <ul>
     *   <li>{@link #REASON_SOURCE_REVOKED}：来源撤回级联取消——属<b>治理类正常动作</b>，
     *       统计失败率时必须<b>剔除</b>，否则一波合法撤回会被当成故障打爆告警；</li>
     *   <li>其他：人工显式放弃。</li>
     * </ul>
     */
    REJECTED;

    /** 来源撤回导致的取消标记（写入 {@code rag_ingest_job.error_detail}）。 */
    public static final String REASON_SOURCE_REVOKED = "SOURCE_REVOKED";

    /** 视为「运行中」的状态集合（Sweeper 扫超时的判据之一）。 */
    public static final List<IngestJobStatus> IN_FLIGHT = List.of(
            PARSING, CHUNKING, EMBEDDING, INDEXING);

    /**
     * 是否为「进行中」状态。
     *
     * @return 进行中返回 true
     */
    public boolean isInFlight() {
        return IN_FLIGHT.contains(this);
    }

    /**
     * 是否为终态（不可再被消费循环领取）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED;
    }

    /**
     * 由字符串安全解析（数据库读回用）。
     *
     * <p>⚠️ 非法值抛异常而非回落默认值：job 状态是审计事实，
     * 静默回落到 PENDING 会让失败任务被反复重跑。
     *
     * @param raw 数据库中的枚举字面量
     * @return 对应枚举
     * @throws IllegalArgumentException 未知状态
     */
    public static IngestJobStatus of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("job status 不能为空");
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的 job 状态：" + raw));
    }
}
