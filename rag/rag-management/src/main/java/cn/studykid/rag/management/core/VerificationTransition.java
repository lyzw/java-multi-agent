package cn.studykid.rag.management.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import cn.studykid.rag.core.source.SourceVerification.VerificationStatus;

/**
 * 语料来源校验状态机（RAG-D07 §2.4 转移表 T-1～T-12）。
 *
 * <p><b>⚠️ 为什么状态机必须显式建模、而不是靠调用方自觉</b>：
 * RAG-D07 §2.4 列出了 12 条转移规则，其中 <b>3 条是禁止转移</b>：
 * <ul>
 *   <li><b>T-10</b> {@code REVOKED → 任意} ❌——REVOKED 是<b>终态</b>。
 *       把被撤回的来源「复活」会让历史撤回记录与当前状态互相矛盾；
 *       重新采集同一出版物应当是<b>新的 source_id</b>。</li>
 *   <li><b>T-11</b> {@code VERIFIED → PENDING} ❌——不允许「降级回默认态」；
 *       重新校验走 T-9（{@code VERIFIED → IN_REVIEW}）。</li>
 *   <li><b>T-12</b> {@code REJECTED → VERIFIED} ❌——<b>禁止跳过 IN_REVIEW 直通</b>，
 *       否则「审核」这一环形同虚设。</li>
 * </ul>
 *
 * <p>把转移表编码为<b>领域规则</b>（而非散落在 Controller/Service 的 if 判断），
 * 才能让「非法转移被拒」成为<b>可测的自动化断言</b>，
 * 并让每条规则都有<b>反证用例</b>（见 {@code VerificationTransitionTest}）。
 *
 * <p><b>⚠️ 与「过期」的区别</b>：过期（stale）<b>不是</b>状态位，
 * 而是 {@code verified_at + ver_valid_days < now()} 的<b>计算属性</b>（RAG-D07 §3.4）。
 * 过期后走 T-9 重新校验，<b>不能</b>用「过期」当作第 6 个状态。
 */
public final class VerificationTransition {

    private VerificationTransition() {
    }

    /**
     * 合法转移白名单（键为当前状态，值为允许到达的目标状态集合）。
     *
     * <p>⚠️ 逐条对应 RAG-D07 §2.4：
     * <ul>
     *   <li>T-1 {@code PENDING → IN_REVIEW}</li>
     *   <li>T-2 {@code IN_REVIEW → VERIFIED}</li>
     *   <li>T-3 {@code IN_REVIEW → REJECTED}</li>
     *   <li>T-4 {@code REJECTED → IN_REVIEW}</li>
     *   <li>T-5 {@code PENDING → REVOKED}</li>
     *   <li>T-6 {@code IN_REVIEW → REVOKED}</li>
     *   <li>T-7 {@code VERIFIED → REVOKED}</li>
     *   <li>T-8 {@code REJECTED → REVOKED}</li>
     *   <li>T-9 {@code VERIFIED → IN_REVIEW}（到期重校）</li>
     * </ul>
     */
    private static final Map<VerificationStatus, Set<VerificationStatus>> ALLOWED = Map.of(
            VerificationStatus.PENDING,
            EnumSet.of(VerificationStatus.IN_REVIEW, VerificationStatus.REVOKED),
            VerificationStatus.IN_REVIEW,
            EnumSet.of(VerificationStatus.VERIFIED, VerificationStatus.REJECTED,
                    VerificationStatus.REVOKED),
            VerificationStatus.VERIFIED,
            EnumSet.of(VerificationStatus.IN_REVIEW, VerificationStatus.REVOKED),
            VerificationStatus.REJECTED,
            EnumSet.of(VerificationStatus.IN_REVIEW, VerificationStatus.REVOKED),
            // ⚠️ REVOKED 是终态：允许转移集合为空（不是 null），
            //    使「任何转移都不合法」成为可断言的事实而非未定义行为。
            VerificationStatus.REVOKED,
            EnumSet.noneOf(VerificationStatus.class));

    /**
     * 判定一次状态转移是否合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 合法返回 {@code true}
     */
    public static boolean isAllowed(VerificationStatus from, VerificationStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(VerificationStatus.class)).contains(to);
    }

    /**
     * 校验一次状态转移，非法时抛出带说明的异常。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws IllegalStateException 转移不在 RAG-D07 §2.4 白名单内
     */
    public static void requireAllowed(VerificationStatus from, VerificationStatus to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStateException(
                    "非法的校验状态转移：" + from + " → " + to
                            + "（RAG-D07 §2.4 转移表未允许该转移）");
        }
    }

    /**
     * @param from 当前状态
     * @return 某状态下允许到达的目标状态集合（<b>不可变</b>快照）
     */
    public static Set<VerificationStatus> allowedTargets(VerificationStatus from) {
        if (from == null) {
            return Set.of();
        }
        Set<VerificationStatus> targets =
                ALLOWED.getOrDefault(from, EnumSet.noneOf(VerificationStatus.class));
        // ⚠️ 必须返回**不可变**集合：EnumSet.copyOf 返回的仍是可变集合，
        //    调用方拿到后 add() 就能污染这张白名单表——而这张表正是
        //    「非法状态转移被拒」的唯一依据，绝不能被外部改写。
        //    Set.copyOf 对空集合安全（返回空不可变集），无需像 EnumSet.copyOf
        //    那样特判空集。
        return Set.copyOf(targets);
    }
}
