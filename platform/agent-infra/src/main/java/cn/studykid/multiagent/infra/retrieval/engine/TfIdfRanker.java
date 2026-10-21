package cn.studykid.multiagent.infra.retrieval.engine;

import java.util.Arrays;

/**
 * TF-IDF 排序组件（G3 混合检索的 keyword 分支）。
 */
public final class TfIdfRanker {

    private static final double DEFAULT_TF_WEIGHT = 0.5;
    private static final double DEFAULT_IDF_WEIGHT = 0.5;

    private final int maxResultsLimit;
    private final double tfWeight;
    private final double idfWeight;

    public TfIdfRanker(int defaultMaxResults, double tfWeight, double idfWeight) {
        if (defaultMaxResults <= 0) {
            throw new IllegalArgumentException("maxResultsLimit 必须为正");
        }
        this.maxResultsLimit = defaultMaxResults;
        this.tfWeight = tfWeight;
        this.idfWeight = idfWeight;
    }

    /** TF-IDF 打分，归一到 [0,1]。 */
    public double score(String terms) {
        if (terms == null || terms.isBlank()) {
            return 0.0;
        }
        String[] tokens = terms.toLowerCase().split("\\W+");
        long freq = Arrays.stream(tokens).filter(t -> !t.isEmpty()).count();
        double raw = (tfWeight * freq) * idfWeight;
        return Math.min(1.0, raw);
    }

    public int getMaxResultsLimit() {
        return maxResultsLimit;
    }

    public double getTfWeight() {
        return tfWeight;
    }

    public double getIdfWeight() {
        return idfWeight;
    }
}
