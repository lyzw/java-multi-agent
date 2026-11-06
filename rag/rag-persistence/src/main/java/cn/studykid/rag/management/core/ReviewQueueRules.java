package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 审核待办的「下一步」判定（交互文档 §4.6「审核收件箱」）。
 *
 * <p><b>为什么由服务端算，而不是前端判断</b>：审核下一步取决于
 * 元数据确认、抽检状态、校验状态、采集人身份四个条件的组合。
 * 前端一旦自行推算，就会与后端状态机守卫（{@code VerificationTransition}）
 * 与抽检守卫（{@code InspectionGuard}）的口径漂移——
 * 典型事故是界面给了一个点下去必然 409 的按钮。
 *
 * <p>本类只做**判定**，不查库、不改状态，故可穷举单测。
 */
public final class ReviewQueueRules {

    private ReviewQueueRules() {
    }

    /**
     * 审核流程的「下一步」。
     *
     * <p>⚠️ 顺序就是流程：确认元数据 → 抽检 → 认领 → 签发。
     * 跳过任何一步都会被后端拒绝，故这里的顺序必须与守卫一致。
     */
    public enum Stage {
        /** 元数据未确认：先确认书名/出版社等，否则审核的是机器猜的值。 */
        CONFIRM_METADATA("确认元数据", "系统推断的元数据尚未确认，请先核对书名与出版社"),

        /** 尚未抽检：签发前必须先过抽检（漏判=0 单点否决）。 */
        NEEDS_INSPECTION("开始抽检", "尚未抽检。请核对答案区有无漏判，再提交抽检结论"),

        /** 抽检不合格：需修正切分后重跑，不能直接签发。 */
        INSPECTION_FAILED("需重新处理", "抽检未通过（存在漏判或错误），需修正后重新处理再抽检"),

        /** 抽检已通过、尚未认领：下一步是认领进入审核中。 */
        READY_TO_CLAIM("认领并审核", "抽检已通过，可认领进入审核"),

        /** 审核中：等待签发或拒绝。 */
        IN_REVIEW("签发或拒绝", "已认领，核对后签发（需两位不同主体）或拒绝"),

        /** 已通过：不需要再审，除非到期重校。 */
        DONE("已完成", "该来源已通过校验，无需再审核"),

        /** 终态：撤回。 */
        REVOKED("已撤回", "该来源已撤回（终态），不可再审核");

        private final String label;
        private final String description;

        Stage(String label, String description) {
            this.label = label;
            this.description = description;
        }

        /** 按钮/徽标上的短语。 */
        public String label() {
            return label;
        }

        /** 面向用户的一句说明。 */
        public String description() {
            return description;
        }
    }

    /**
     * 判定下一步。
     *
     * @param verificationStatus 校验状态（PENDING / IN_REVIEW / VERIFIED / REJECTED / REVOKED）
     * @param metadataConfirmed  元数据是否已确认
     * @param inspectionStatus   抽检状态（NOT_REQUIRED / PASSED / FAILED，可空视为未抽检）
     * @return 下一步阶段
     */
    @NonNull
    public static Stage nextStage(@Nullable String verificationStatus,
                                  boolean metadataConfirmed,
                                  @Nullable String inspectionStatus) {
        String status = verificationStatus == null ? "PENDING" : verificationStatus.trim();

        // ⚠️ 终态优先：撤回后无论其它字段如何都不再审核
        if ("REVOKED".equalsIgnoreCase(status)) {
            return Stage.REVOKED;
        }
        if ("VERIFIED".equalsIgnoreCase(status)) {
            return Stage.DONE;
        }
        // 审核中：已认领，下一步就是签发/拒绝（抽检必已通过，否则认领会失败）
        if ("IN_REVIEW".equalsIgnoreCase(status)) {
            return Stage.IN_REVIEW;
        }

        // 以下为 PENDING / REJECTED（可重新认领）
        // ① 元数据未确认：先确认
        if (!metadataConfirmed) {
            return Stage.CONFIRM_METADATA;
        }
        // ② 抽检未通过
        String inspection = inspectionStatus == null ? "NOT_REQUIRED" : inspectionStatus.trim();
        if ("FAILED".equalsIgnoreCase(inspection)) {
            return Stage.INSPECTION_FAILED;
        }
        if (!"PASSED".equalsIgnoreCase(inspection)) {
            // NOT_REQUIRED 或未知：都当作「还没抽」
            return Stage.NEEDS_INSPECTION;
        }
        // ③ 抽检已过，可认领
        return Stage.READY_TO_CLAIM;
    }

    /**
     * 当前调用者能否执行该步骤。
     *
     * <p>⚠️ **本方法只判「已知的硬约束」**，不猜 IAM 角色：
     * 目前能确定的是「审核员不得审核自己采集的来源」（SoD），
     * 以及「撤回/已完成无需再动手」。角色级权限由后端守卫在提交时校验，
     * 这里不做前端臆测——否则界面会凭一个猜出来的角色矩阵误导人。
     *
     * @param stage        下一步阶段
     * @param registeredBy 采集人主体 ID
     * @param currentUser  当前调用者主体 ID
     * @return 可否执行
     */
    public static boolean canActorProceed(@NonNull Stage stage,
                                          @Nullable String registeredBy,
                                          @Nullable String currentUser) {
        // 终态与已完成无需动作
        if (stage == Stage.DONE || stage == Stage.REVOKED) {
            return false;
        }
        // ⚠️ 确认元数据**允许**采集人自己做：那正是「我录的我确认」，
        //    它是审核的前置，不是审核本身，故不受 SoD 限制。
        if (stage == Stage.CONFIRM_METADATA) {
            return true;
        }
        // 其余步骤属于审核动作：采集人不得自己做（自审 = 责任不可追溯）
        if (registeredBy != null && currentUser != null
                && !registeredBy.isBlank() && registeredBy.equalsIgnoreCase(currentUser)) {
            return false;
        }
        return true;
    }

    /**
     * 不可执行的原因（人话）。
     *
     * @param stage        下一步阶段
     * @param registeredBy 采集人
     * @param currentUser  当前调用者
     * @return 原因；可执行时返回 null
     */
    @Nullable
    public static String cantActReason(@NonNull Stage stage,
                                       @Nullable String registeredBy,
                                       @Nullable String currentUser) {
        if (stage == Stage.DONE) {
            return "该来源已通过校验，无需再审核";
        }
        if (stage == Stage.REVOKED) {
            return "该来源已撤回（终态），不可再审核";
        }
        if (stage == Stage.CONFIRM_METADATA) {
            return null;
        }
        if (registeredBy != null && currentUser != null
                && !registeredBy.isBlank() && registeredBy.equalsIgnoreCase(currentUser)) {
            return "这是你自己采集的语料，按治理规则不能由你审核（需换一位审核员）";
        }
        return null;
    }

    /**
     * 该步骤是否属于「审核动作」（受 SoD 约束）。
     *
     * <p>供控制器/界面区分「这是审核前的准备」还是「这是审核本身」。
     */
    public static boolean isReviewAction(@NonNull Stage stage) {
        return stage == Stage.NEEDS_INSPECTION
                || stage == Stage.READY_TO_CLAIM
                || stage == Stage.IN_REVIEW;
    }
}
