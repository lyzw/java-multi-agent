package cn.studykid.rag.core.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link IngestJobStatus} / {@link IngestJob} 的守卫测试（S1-2 / S1-5 / N2 反证）。
 *
 * <p>这些断言锁住的是<b>方案层面的不变式</b>，不是实现细节：
 * 枚举不得增删值、失败必有原因、撤回取消不得计入失败率。
 */
class IngestJobTest {

    @Nested
    @DisplayName("状态枚举与 DDL 枚举值严格一致")
    class EnumAlignment {

        @Test
        @DisplayName("8 个值不多不少（改 DDL 枚举或改本枚举都会让此断言失败）")
        void hasExactlyEightValues() {
            assertEquals(8, IngestJobStatus.values().length);
        }

        @Test
        @DisplayName("进行中状态集合恰为 PARSING/CHUNKING/EMBEDDING/INDEXING")
        void inFlightSetIsExact() {
            assertEquals(java.util.List.of(
                            IngestJobStatus.PARSING, IngestJobStatus.CHUNKING,
                            IngestJobStatus.EMBEDDING, IngestJobStatus.INDEXING),
                    IngestJobStatus.IN_FLIGHT);
        }

        @Test
        @DisplayName("PENDING 不算进行中（Sweeper 不得接管尚未领取的任务）")
        void pendingIsNotInFlight() {
            assertFalse(IngestJobStatus.PENDING.isInFlight());
            assertFalse(IngestJobStatus.PENDING.isTerminal());
        }

        @Test
        @DisplayName("终态恰为 SUCCEEDED/FAILED/REJECTED")
        void terminalSetIsExact() {
            assertTrue(IngestJobStatus.SUCCEEDED.isTerminal());
            assertTrue(IngestJobStatus.FAILED.isTerminal());
            assertTrue(IngestJobStatus.REJECTED.isTerminal());
            assertFalse(IngestJobStatus.INDEXING.isTerminal());
        }

        @Test
        @DisplayName("未知状态字面量必须抛异常，不得静默回落 PENDING")
        void unknownStatusThrows() {
            assertThrows(IllegalArgumentException.class, () -> IngestJobStatus.of("RUNNING"));
            assertThrows(IllegalArgumentException.class, () -> IngestJobStatus.of("DONE"));
        }
    }

    @Nested
    @DisplayName("job 不变式")
    class Invariants {

        @Test
        @DisplayName("FAILED 必须带 errorDetail")
        void failedRequiresErrorDetail() {
            assertThrows(IllegalArgumentException.class, () -> new IngestJob(
                    "j", "t", "s", IngestJobStatus.FAILED, null, 0, null,
                    null, null, null, null, "start", "finish", "created"));
        }

        @Test
        @DisplayName("终态必须带 finishedAt")
        void terminalRequiresFinishedAt() {
            assertThrows(IllegalArgumentException.class, () -> new IngestJob(
                    "j", "t", "s", IngestJobStatus.SUCCEEDED, null, 0, null,
                    null, null, 10, null, "start", null, "created"));
        }

        @Test
        @DisplayName("合法的 FAILED job 可构造")
        void validFailedJob() {
            IngestJob job = new IngestJob("j", "t", "s", IngestJobStatus.FAILED, null, 0, null,
                    null, null, null, "boom", "start", "finish", "created");
            assertNotNull(job);
            assertTrue(job.countsAsFailure());
        }
    }

    @Nested
    @DisplayName("N2：来源撤回取消的语义")
    class RevokedCancel {

        @Test
        @DisplayName("REJECTED + SOURCE_REVOKED 不计入失败率")
        void revokedCancelIsNotFailure() {
            IngestJob job = new IngestJob("j", "t", "s", IngestJobStatus.REJECTED, null, 0, null,
                    null, null, null, IngestJobStatus.REASON_SOURCE_REVOKED,
                    "start", "finish", "created");
            assertTrue(job.isSourceRevokedCancel());
            assertFalse(job.countsAsFailure(), "撤回取消是治理动作的正常结果，计入会打爆失败率告警");
        }

        @Test
        @DisplayName("人工放弃（非 SOURCE_REVOKED）不算撤回取消")
        void manualRejectIsNotRevokedCancel() {
            IngestJob job = new IngestJob("j", "t", "s", IngestJobStatus.REJECTED, null, 0, null,
                    null, null, null, "MANUAL_ABORT", "start", "finish", "created");
            assertFalse(job.isSourceRevokedCancel());
        }
    }

    @Nested
    @DisplayName("去重口径（N1）")
    class DedupRules {

        @Test
        @DisplayName("载体活跃谓词必须同时排除 REVOKED 与 FAILED")
        void documentPredicateExcludesBoth() {
            String p = IngestDedupRules.ACTIVE_DOCUMENT_PREDICATE;
            assertTrue(p.contains("REVOKED"), "不排除 REVOKED 会堵死撤回重传路径");
            assertTrue(p.contains("FAILED"), "不排除 FAILED 会堵死失败后重传重跑");
        }

        @Test
        @DisplayName("来源活跃谓词必须排除 REVOKED")
        void sourcePredicateExcludesRevoked() {
            assertTrue(IngestDedupRules.ACTIVE_SOURCE_PREDICATE.contains("REVOKED"),
                    "不排除 REVOKED 会与 T-10「撤回后新建 source_id 重登记」冲突");
        }

        @Test
        @DisplayName("三层错误码固定")
        void errorCodes() {
            assertEquals("DUP_SOURCE", IngestDedupRules.Layer.SOURCE.errorCode());
            assertEquals("DUP_EDITION", IngestDedupRules.Layer.EDITION.errorCode());
            assertEquals("DUP_CARRIER", IngestDedupRules.Layer.CARRIER.errorCode());
        }
    }
}
