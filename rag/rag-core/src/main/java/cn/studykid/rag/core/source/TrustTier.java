package cn.studykid.rag.core.source;

import org.jspecify.annotations.NonNull;

/**
 * 数据源权威等级（RAG-D00 §2.1 决策 D-R01）。
 *
 * <p><b>⚠️ 为什么需要分级而不是布尔值「可信/不可信」</b>：
 * 教辅语料的权威性是<b>连续</b>的，不是二元的。同一本教材的
 * 「正文」与「课后习题答案」权威性天然不同；论文的
 * 「同行评审版本」与「预印本」也不是同一等级。
 * 用布尔值会逼着系统把「答案册」和「教材正文」等价看待，
 * 而这正是教辅场景最危险的错误来源。
 *
 * <p><b>等级如何被使用</b>（不可绕过）：
 * <ol>
 *   <li><b>准入</b>：{@link VerificationStatus#VERIFIED} 是入索引的<b>必要</b>条件，
 *       但只有本等级 ≥ {@code MIN_AUTHORITY} 的源才允许被
 *       <b>普通用户</b>检索到；低等级源可用于内部检索与对照，
 *       但不得作为教学回答的唯一依据。</li>
 *   <li><b>冲突仲裁</b>：同一知识点在不同等级来源结论冲突时，
 *       高等级优先；同等级冲突时必须<b>并列呈现</b>并标注分歧，
 *       不允许静默择一。</li>
 * </ol>
 *
 * <p><b>⚠️ 注意</b>：等级<b>不</b>代表内容正确性。它只表示
 * 「该来源在出版/审校链条上的位置」。教学正确性由
 * {@code rag-core} 的溯源与蕴含校验负责，<b>不能</b>用权威等级
 * 替代——否则「权威但陈旧」和「非权威但正确」会被混为一谈。
 */
public enum TrustTier {

    /** 教材出版社官方出版（人教/苏教/北师大等），经 ISBN 校验。 */
    OFFICIAL_PUBLISHER(4),

    /**
     * 教辅品牌正式出版（经三审三校），经 ISBN 校验。
     *
     * <p>⚠️ 与 {@link #OFFICIAL_PUBLISHER} 的差别是<b>教学取向</b>而非正确性：
     * 教辅的口径可能与教材不一致，故引用时须同时给出教材出处。
     */
    OFFICIAL_TUTORIAL(3),

    /** 学术论文（同行评审），经 DOI 校验。 */
    PEER_REVIEWED_PAPER(2),

    /**
     * 预印本/会议摘要/机构白皮书 —— 未经同行评审。
     *
     * <p>⚠️ 可作为「参考观点」，<b>不可</b>作为定义性结论的唯一依据。
     */
    PREPRINT(1),

    /**
     * 未校验来源（个人上传、扫描件、网页存档）。
     *
     * <p>⚠️ 存在<b>盗版教材</b>与<b>篡改扫描件</b>的实质风险。
     * 默认<b>不可</b>被检索到，除非经人工审核逐条提升等级。
     */
    UNVERIFIED(0);

    private final int weight;

    TrustTier(int weight) {
        this.weight = weight;
    }

    /** @return 权重，数值越大越权威；用于比较与排序。 */
    public int weight() {
        return weight;
    }

    /** @return 是否达到给定的最低权威门槛（含等于）。 */
    public boolean meets(@NonNull TrustTier minimum) {
        return this.weight >= minimum.weight;
    }

    /**
     * @return 该等级是否允许作为<b>教学回答的唯一支撑</b>。
     *
     * <p>只有官方教材与正式教辅可以单独支撑一条教学结论；
     * 论文/预印本/未校验来源必须<b>有官方来源并置</b>。
     */
    public boolean standaloneSupportAllowed() {
        return this == OFFICIAL_PUBLISHER || this == OFFICIAL_TUTORIAL;
    }
}
