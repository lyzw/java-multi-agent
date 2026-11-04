package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审核待办「下一步」判定（交互文档 §4.6「审核收件箱」）。
 *
 * <p>⚠️ 守三件事：
 * <ol>
 *   <li><b>顺序不可跳</b>：确认元数据 → 抽检 → 认领 → 签发。
 *       跳步会让界面给出必然 409 的按钮；</li>
 *   <li><b>终态不进待办</b>：已完成/已撤回不占待办位；</li>
 *   <li><b>采集人不得自审</b>，但<b>可以自己确认元数据</b>（那是审核前的准备）。</li>
 * </ol>
 */
class ReviewQueueRulesTest {

    @Test
    @DisplayName("元数据未确认 → 先确认元数据")
    void unconfirmedMetadataFirst() {
        assertEquals(ReviewQueueRules.Stage.CONFIRM_METADATA,
                ReviewQueueRules.nextStage("PENDING", false, "NOT_REQUIRED"));
    }

    @Test
    @DisplayName("已确认但未抽检 → 开始抽检")
    void confirmedButNotInspected() {
        assertEquals(ReviewQueueRules.Stage.NEEDS_INSPECTION,
                ReviewQueueRules.nextStage("PENDING", true, "NOT_REQUIRED"));
    }

    @Test
    @DisplayName("抽检不合格 → 需重新处理（不能直接签发）")
    void inspectionFailedBlocksSigning() {
        assertEquals(ReviewQueueRules.Stage.INSPECTION_FAILED,
                ReviewQueueRules.nextStage("PENDING", true, "FAILED"));
    }

    @Test
    @DisplayName("确认 + 抽检通过 → 可认领")
    void readyToClaim() {
        assertEquals(ReviewQueueRules.Stage.READY_TO_CLAIM,
                ReviewQueueRules.nextStage("PENDING", true, "PASSED"));
    }

    @Test
    @DisplayName("审核中 → 签发或拒绝")
    void inReviewStage() {
        assertEquals(ReviewQueueRules.Stage.IN_REVIEW,
                ReviewQueueRules.nextStage("IN_REVIEW", true, "PASSED"));
    }

    @Test
    @DisplayName("已通过 → 已完成（不进待办动作）")
    void verifiedIsDone() {
        assertEquals(ReviewQueueRules.Stage.DONE,
                ReviewQueueRules.nextStage("VERIFIED", true, "PASSED"));
    }

    @Test
    @DisplayName("已撤回 → 终态，任何字段组合都不可再审")
    void revokedIsTerminal() {
        // 撤回后即使元数据未确认、抽检未做，也不再要求任何动作
        assertEquals(ReviewQueueRules.Stage.REVOKED,
                ReviewQueueRules.nextStage("REVOKED", false, "NOT_REQUIRED"));
        assertEquals(ReviewQueueRules.Stage.REVOKED,
                ReviewQueueRules.nextStage("REVOKED", true, "PASSED"));
    }

    @Test
    @DisplayName("已拒绝 → 可重新认领（回到 PENDING 流程）")
    void rejectedCanReclaim() {
        assertEquals(ReviewQueueRules.Stage.READY_TO_CLAIM,
                ReviewQueueRules.nextStage("REJECTED", true, "PASSED"));
    }

    @Test
    @DisplayName("未知/空状态按未抽检处理，不猜「已通过」")
    void unknownInspectionTreatedAsNotDone() {
        assertEquals(ReviewQueueRules.Stage.NEEDS_INSPECTION,
                ReviewQueueRules.nextStage("PENDING", true, null));
        assertEquals(ReviewQueueRules.Stage.NEEDS_INSPECTION,
                ReviewQueueRules.nextStage("PENDING", true, "SOMETHING_NEW"));
    }

    @Test
    @DisplayName("⭐ 采集人可以自己确认元数据（那是审核前的准备，不是审核）")
    void creatorMayConfirmOwnMetadata() {
        String user = "u-creator";
        assertTrue(ReviewQueueRules.canActorProceed(
                ReviewQueueRules.Stage.CONFIRM_METADATA, user, user));
    }

    @Test
    @DisplayName("⛔ 采集人不得审核自己采集的来源")
    void creatorCannotReviewOwnSource() {
        String user = "u-creator";
        for (ReviewQueueRules.Stage stage : new ReviewQueueRules.Stage[] {
                ReviewQueueRules.Stage.NEEDS_INSPECTION,
                ReviewQueueRules.Stage.READY_TO_CLAIM,
                ReviewQueueRules.Stage.IN_REVIEW}) {
            assertFalse(ReviewQueueRules.canActorProceed(stage, user, user),
                    "阶段 " + stage + " 应拒绝自审");
            assertTrue(ReviewQueueRules.cantActReason(stage, user, user).contains("自己采集"));
        }
    }

    @Test
    @DisplayName("采集人缺失（历史数据无审计记录）→ 不阻断审核")
    void nullRegistrantDoesNotBlock() {
        // ⚠️ 历史数据读不到登记人时按「无记录」处理，
        //    否则会把「审核」这个核心动作永久卡死。
        assertTrue(ReviewQueueRules.canActorProceed(
                ReviewQueueRules.Stage.READY_TO_CLAIM, null, "u-auditor"));
        assertNull(ReviewQueueRules.cantActReason(
                ReviewQueueRules.Stage.READY_TO_CLAIM, null, "u-auditor"));
    }

    @Test
    @DisplayName("其他人可以审核")
    void otherUserMayReview() {
        assertTrue(ReviewQueueRules.canActorProceed(
                ReviewQueueRules.Stage.READY_TO_CLAIM, "u-creator", "u-auditor"));
    }

    @Test
    @DisplayName("终态不可执行任何动作")
    void terminalStagesNotActionable() {
        assertFalse(ReviewQueueRules.canActorProceed(
                ReviewQueueRules.Stage.DONE, "u-x", "u-y"));
        assertFalse(ReviewQueueRules.canActorProceed(
                ReviewQueueRules.Stage.REVOKED, "u-x", "u-y"));
    }

    @Test
    @DisplayName("isReviewAction 区分「审核」与「审核前准备」")
    void reviewActionClassification() {
        assertFalse(ReviewQueueRules.isReviewAction(ReviewQueueRules.Stage.CONFIRM_METADATA));
        assertTrue(ReviewQueueRules.isReviewAction(ReviewQueueRules.Stage.NEEDS_INSPECTION));
        assertTrue(ReviewQueueRules.isReviewAction(ReviewQueueRules.Stage.READY_TO_CLAIM));
        assertTrue(ReviewQueueRules.isReviewAction(ReviewQueueRules.Stage.IN_REVIEW));
    }

    @Test
    @DisplayName("实体：withViewer 补全 canAct 与原因，且不因采集人缺失而误判")
    void entryWithViewer() {
        ReviewQueueEntry base = new ReviewQueueEntry(
                "s-1", "书名", "出版社", "PENDING", true, "PASSED",
                1, 100, 5, "u-creator", ReviewQueueRules.Stage.READY_TO_CLAIM,
                false, null, "2026-11-03T00:00:00Z");

        // 采集人自己看：不可审
        ReviewQueueEntry self = base.withViewer("u-creator");
        assertFalse(self.canAct());
        assertTrue(self.cantActReason().contains("自己采集"));

        // 另一位审核员看：可以审
        ReviewQueueEntry other = base.withViewer("u-auditor");
        assertTrue(other.canAct());
        assertNull(other.cantActReason());
    }
}
