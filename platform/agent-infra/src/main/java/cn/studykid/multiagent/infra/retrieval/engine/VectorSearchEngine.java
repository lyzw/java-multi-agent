package cn.studykid.multiagent.infra.retrieval.engine;

/**
 * 向量检索客户端（G3 占位实现）。
 *
 * <p>⚠️ 真实 BERT 向量库为外部依赖（A-008 阻断）。此处提供占位语义分，
 * 使 Authority Triple Gate 的语义分门可被单元测试确定性触发。
 * {@link #setBaseScoreForTest(double)} 仅供测试注入低分以触发阈值拒绝。
 */
public final class VectorSearchEngine {

    private static final double DEFAULT_SCORE = 0.85;

    private double baseScore = DEFAULT_SCORE;

    /** 返回给定上下文的语义相似度分（占位实现）。 */
    public double score(RetrievalService.ContextRecord record) {
        return baseScore;
    }

    /** 测试桩：注入自定义语义分（如低于 0.75 触发阈值拒绝）。 */
    public void setBaseScoreForTest(double score) {
        this.baseScore = score;
    }
}
