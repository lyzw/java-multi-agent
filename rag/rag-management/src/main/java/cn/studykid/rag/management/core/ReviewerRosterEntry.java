package cn.studykid.rag.management.core;

import org.jspecify.annotations.NonNull;

/**
 * 审核员名录条目（D-001 的 RAG 侧授权投影）。
 *
 * <p><b>它解决什么</b>：签发表单的「复核人」此前要**手输 UUID**
 * （交互文档问题 P5）。而 RAG 与平台是两个独立数据库，
 * RAG 看不到 {@code public.principal}，也没有任何 HTTP 客户端可回调平台。
 * 故 RAG 侧需要一份**自己的**审核员名录：只登记「谁能在 RAG 做审核」，
 * 不存密码、不存登录名、不存真实姓名。
 *
 * <p><b>⚠️ 这不是 IAM 的替代品</b>：D-001 §3.2 明确「JWT 里的 roles 只是声明，
 * 实际生效的角色以<b>库中登记</b>为准」。本名录正是那份 RAG 侧的登记，
 * 是 IAM 的授权投影，不是另一套账号体系。
 *
 * <p><b>⚠️ display_name 不得含真实姓名</b>（DEC-6 / PIPL）：
 * 存的是「教研组-王老师」这类内部标识，不是自然人姓名。
 *
 * @param principalId 主体 ID（与平台 JWT 的 sub 一致）
 * @param displayName 显示名（下拉里给人看的那一行）
 * @param canReview   是否可执行审核（抽检 / 认领 / 签发）
 * @param canCoSign   是否可做复核人（双人签署的第二签）
 * @param enabled     是否启用（停用不删除，历史签署记录仍要能解析「当时是谁」）
 */
public record ReviewerRosterEntry(
        @NonNull String principalId,
        @NonNull String displayName,
        boolean canReview,
        boolean canCoSign,
        boolean enabled
) {
}
