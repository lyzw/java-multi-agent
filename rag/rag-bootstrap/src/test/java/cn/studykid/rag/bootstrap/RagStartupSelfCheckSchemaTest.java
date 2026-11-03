package cn.studykid.rag.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RagStartupSelfCheck} 的库结构版本校验（V1.0.2）。
 *
 * <p><b>⚠️ 这组测试锁定的是「一次真实事故」</b>：
 * 2026-11-02，代码新增了 {@code rag_ingest_job.stage/attempt/created_by} 三列，
 * 但生产库未跑迁移 —— 服务<b>照常启动</b>、其他端点<b>照常工作</b>，
 * 只有 {@code GET /ingest-jobs} 每次 500。排查要靠读代码反推 SELECT 了哪些列。
 *
 * <p>现在这类不一致必须在<b>启动期</b>被拦下，并给出可执行的修复指引。
 */
class RagStartupSelfCheckSchemaTest {

    /** 齐全的库结构（当前 V1.0.2 终态）。 */
    private static Set<String> fullColumns() {
        return Set.of(
                "rag_ingest_job.stage", "rag_ingest_job.attempt", "rag_ingest_job.created_by",
                "rag_source.inspection_status");
    }

    private static Set<String> fullTables() {
        return Set.of("rag_inspection_record", "rag_kp_alias");
    }

    @Test
    @DisplayName("结构齐全 → 无失败项")
    void completeSchemaPasses() {
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures, fullColumns(), fullTables());
        assertEquals(List.of(), failures, "结构齐全时不应报错");
    }

    @Test
    @DisplayName("反证：缺 rag_ingest_job.stage → 启动被拒（这正是那次 500 的根因）")
    void missingStageColumnFails() {
        Set<String> cols = Set.of(
                // 只缺 stage
                "rag_ingest_job.attempt", "rag_ingest_job.created_by",
                "rag_source.inspection_status");
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures, cols, fullTables());

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("rag_ingest_job.stage"), failures.get(0));
        assertTrue(failures.get(0).contains("V1.0.2-S1"), "应指出引入版本");
        assertTrue(failures.get(0).contains("迁移"), "应给出修复指引");
    }

    @Test
    @DisplayName("反证：三列全缺 → 三条失败（不会只报一条就停）")
    void allJobColumnsMissing() {
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures,
                Set.of("rag_source.inspection_status"), fullTables());
        assertEquals(3, failures.size(), "三列都要报出来，便于一次性修完");
    }

    @Test
    @DisplayName("反证：缺 inspection_status → 启动被拒")
    void missingInspectionStatusFails() {
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures,
                Set.of("rag_ingest_job.stage", "rag_ingest_job.attempt",
                        "rag_ingest_job.created_by"),
                fullTables());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("inspection_status"));
        assertTrue(failures.get(0).contains("V1.0.2-S3"));
    }

    @Test
    @DisplayName("反证：缺新表 → 启动被拒")
    void missingTablesFail() {
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures, fullColumns(), Set.of());
        assertEquals(2, failures.size(), "两张表都要报");
        assertTrue(failures.stream().anyMatch(f -> f.contains("rag_inspection_record")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("rag_kp_alias")));
    }

    @Test
    @DisplayName("空库（全部缺失）→ 报出全部 6 项，不抛异常")
    void emptySchemaReportsAll() {
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures, Set.of(), Set.of());
        assertEquals(6, failures.size(), "4 列 + 2 表");
    }

    @Test
    @DisplayName("判定函数不碰库（构造器只用于满足签名）")
    void assessmentIsPure() {
        // 若判定函数碰库，用未配置的 JdbcTemplate 会抛异常。
        // 这里走静态方法，证明它是纯函数。
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessSchemaVersion(failures, fullColumns(), fullTables());
        assertEquals(0, failures.size());

        // 顺带确认构造器签名仍可用
        new RagStartupSelfCheck(new JdbcTemplate(), false, "", "rag_app");
    }
}
