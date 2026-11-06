package cn.studykid.rag.management.core;

import java.util.Set;

import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;

/**
 * 载体级校验规则（RAG-D07 §2.3.1 G-0 / §2.4 转移表 / RAG-D03 §4 三层模型）。
 *
 * <p><b>⚠️ 为什么载体级要另立一套规则，而不是直接复用来源级</b>：
 * 载体与来源是<b>两个独立轴</b>。来源回答「这部作品是否可信」，
 * 载体回答「这一份物理文件是否可用」。两者都合法，但组合起来有约束：
 *
 * <ul>
 *   <li><b>载体不得高于来源</b>：来源没通过校验，载体不能自称 VERIFIED。
 *       否则会出现「来源 PENDING、载体 VERIFIED」这种自相矛盾的记录——
 *       检索侧只要看载体标记，就会召回尚未审核的语料。</li>
 *   <li><b>载体可以低于来源</b>：来源 VERIFIED、载体 REJECTED 是合法状态
 *       （官方电子版可信，同版本的第三方扫描版质量不合格）。
 *       这正是三层模型存在的意义（RAG-D03 §4）。</li>
 * </ul>
 *
 * <p><b>⚠️ 状态机复用而非另立</b>：载体的状态取值与来源同为
 * {@link VerificationStatus}，合法转移也复用
 * {@link VerificationTransition#requireAllowed}——
 * 「REVOKED 是终态」「REJECTED 不得直通 VERIFIED」这类规则
 * 只应有一处定义，否则两处漂移就会放行非法流转。
 *
 * <p><b>⚠️ 诚实边界：载体级撤回目前只封住「管理面结论」，尚未封住检索期</b>。
 * 本类把撤回落到 {@code rag_document.ver_status='REVOKED'} 与
 * {@code retrievable=false}，管理台因此能如实呈现「这份载体已下架」。
 * 但检索路径（{@code JdbcRetrievalRepository}）当前只过滤
 * {@code src.ver_status = 'VERIFIED'}（来源级），
 * <b>尚未</b>过滤 {@code doc.retrievable}（RAG-D07 §8 登记的 RAG-R01 缺口）。
 * 也就是说：<b>来源级撤回会连带下架载体并真正阻断召回</b>，
 * 而<b>单独撤回某个载体</b>在检索期仍可能被召回——
 * 直到检索侧补上载体级过滤为止。
 * 这一点不得在交付说明里含糊：否则管理台会显示「已下架」，
 * 而学生端仍能检索到内容。
 */
public final class DocumentVerification {

    private DocumentVerification() {
    }

    /**
     * 允许作为<b>载体级目标状态</b>的取值。
     *
     * <p>⚠️ 不含 {@code PENDING}：PENDING 是「从未认领」的<b>初始态</b>，
     * 不是任何一个审核动作的目标。把它列为可选目标会让调用方
     * 写出「把已撤回的载体改回 PENDING」这种「假重置」操作——
     * 表面看是清空状态，实际是绕过撤回语义（撤回是终态，T-10）。
     */
    public static final Set<VerificationStatus> ASSIGNABLE_TARGETS = Set.of(
            VerificationStatus.IN_REVIEW,
            VerificationStatus.VERIFIED,
            VerificationStatus.REJECTED,
            VerificationStatus.REVOKED);

    /**
     * 校验目标状态是否可被指定。
     *
     * @param target 目标状态
     * @throws IllegalArgumentException 目标不在允许集合内（→ HTTP 400）
     */
    public static void requireAssignableTarget(VerificationStatus target) {
        if (target == null || !ASSIGNABLE_TARGETS.contains(target)) {
            throw new IllegalArgumentException("非法的载体级目标状态：" + target
                    + "（合法值：" + ASSIGNABLE_TARGETS.stream().map(Enum::name).sorted().toList() + "）");
        }
    }

    /**
     * 校验载体级状态转移是否合法（复用来源级转移表）。
     *
     * @param from 当前载体状态
     * @param to   目标状态
     * @throws IllegalStateException 转移不在白名单内（→ HTTP 409）
     */
    public static void requireAllowed(VerificationStatus from, VerificationStatus to) {
        // ⚠️ 这里刻意委托给 VerificationTransition 而不是复制一张表：
        //    「REVOKED 是终态」「REJECTED 不得直通 VERIFIED」只应有一处定义。
        VerificationTransition.requireAllowed(from, to);
    }

    /**
     * 校验「载体不得高于来源」。
     *
     * <p>⚠️ 失败时抛 {@link IllegalStateException}（→ HTTP 409）而非 400：
     * 这不是「请求写错了」，而是<b>当前状态不允许该流转</b>——
     * 与非法状态转移同属「治理规则被违反」，运维需要据此区分
     * 「参数问题」与「流程问题」。
     *
     * @param targetStatus 载体目标状态
     * @param sourceStatus 来源当前校验状态
     * @throws IllegalStateException 载体要置 VERIFIED 但来源未通过校验
     */
    public static void requireSourceAllowsVerified(VerificationStatus targetStatus,
                                                   VerificationStatus sourceStatus) {
        if (targetStatus != VerificationStatus.VERIFIED) {
            return;
        }
        if (sourceStatus != VerificationStatus.VERIFIED) {
            throw new IllegalStateException(
                    "载体不得高于来源：来源当前校验状态为 " + sourceStatus
                            + "，未通过校验的来源下的载体不允许置为 VERIFIED"
                            + "（RAG-D07 §2.3.1 G-0：来源级与载体级必须同时通过）");
        }
    }

    /**
     * 目标状态对应的载体级可检索标记。
     *
     * <p>⚠️ 只有 VERIFIED 为真——这是 fail-closed 的直接体现：
     * 「被认领」「被拒绝」都不足以让语料进入检索。
     * 撤回必然落到 false（REVOKED 不在真值集合里）。
     *
     * @param targetStatus 目标状态
     * @return 可检索返回 {@code true}
     */
    public static boolean retrievableAfter(VerificationStatus targetStatus) {
        return targetStatus == VerificationStatus.VERIFIED;
    }

    /**
     * 目标状态 → 审计动作。
     *
     * <p>⚠️ 与来源级用同一套动作枚举（ADMIT / REJECT / REVOKE / INGEST）：
     * 审计是「谁在何时做了什么判定」的<b>事件流</b>，
     * 若载体级另起一套动作名，检索审计记录时就得同时适配两套词表。
     *
     * @param targetStatus 目标状态
     * @return 审计动作
     */
    public static String auditActionFor(VerificationStatus targetStatus) {
        return switch (targetStatus) {
            case VERIFIED -> "ADMIT";
            case REJECTED -> "REJECT";
            case REVOKED -> "REVOKE";
            // ⚠️ PENDING / IN_REVIEW 归入 INGEST：
            //    「登记 + 认领」在语义上属于语料入库流程。
            case PENDING, IN_REVIEW -> "INGEST";
        };
    }
}
