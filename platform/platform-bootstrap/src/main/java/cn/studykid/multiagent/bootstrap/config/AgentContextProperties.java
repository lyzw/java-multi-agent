package cn.studykid.multiagent.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局上下文压缩配置（{@code agent.context.*} 的 Spring 绑定载体，ADR-013 / M2）。
 *
 * <p>⚠️ <b>为什么单独建 properties 类而不是直接绑 {@code GlobalContextPolicy}</b>：
 * 后者是 {@code agent-context} 模块的<b>纯算法 record</b>（零 Spring，守卫
 * {@code contextModuleIsFrameworkFree} 锁死），不能污染它。本类只做
 * 「yml → Java」的<b>配置形状</b>承载，转换在 {@link AgentContextConfiguration} 完成。
 *
 * <p>⚠️ <b>点号/数值绑定的两个坑</b>（均有真实事故）：
 * <ol>
 *   <li>yml 里数值全部带引号（{@code "0.70"}）以绕过 {@code YamlPropertiesFactoryBean}
 *       丢裸标量的问题；Spring Binder 能把带引号的数值串转成 double/int，但
 *       <b>含点号的键会被拆层</b>——所以本类用 JavaBean setter，字段名逐字对应
 *       kebab-case 键，绝不把模型 ID 之类含点号的值当 map 键。</li>
 *   <li>{@code unknownCapabilityReserveRatio} 必须显式声明并给默认值 0.10：
 *       它是 R10（模型能力未知时上调 reserve）的唯一来源，漏绑会得到 0 →
 *       {@code Math.max(reserve, 0)} 永不上调 → <b>不报错、只是 R10 静默失效</b>。</li>
 * </ol>
 *
 * <p>⚠️ {@code enabled} 默认 {@code false} = S1 影子期；所有默认值与
 * {@code application.yml} 的 {@code ${AGENT_CONTEXT_*:默认}} 逐字对齐，
 * 由 {@code AgentContextPropertiesBindingTest} 用真实 Binder 锁死。
 */
@ConfigurationProperties(prefix = "agent.context")
public class AgentContextProperties {

    /** 总熔断；false = 与「没有压缩功能」逐位一致（[D11] / G1）。 */
    private boolean enabled = false;
    /** 全窗口输入硬顶。 */
    private int hardCeilingTokens = 128_000;
    /** Agent 未声明时的输入上限兜底。 */
    private int defaultMaxInputTokens = 16_000;
    private double defaultTriggerRatio = 0.70;
    private double defaultTargetRatio = 0.40;
    private double defaultReserveRatio = 0.10;
    private int defaultRecentTurns = 6;
    /** R10：能力未知时上调到的 reserve 下限。 */
    private double unknownCapabilityReserveRatio = 0.10;
    private double tierSnipRatio = 0.60;
    private double tierSummaryRatio = 0.90;
    private double minNewTokensRatio = 0.10;
    private int maxStateChars = 8_192;
    private int checkpointTtlSeconds = 1_800;
    private int summaryTimeoutMs = 8_000;
    /** 独立 HMAC 密钥，绝不复用 JWT 私钥；影子期可为空。 */
    private String stateHmacKey = "";
    /**
     * S3-M10：L2 SSE 的 RAG 命中区 token 配额（{@code agent.context.rag-quota.*}）。
     * ⚠️ 只影响 {@code enabled=true} 时的注入；关闭态仍走 legacy 固定口径逐位等价。
     */
    private RagQuota ragQuota = new RagQuota();
    /** 估算校准子配置。 */
    private Calibration calibration = new Calibration();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getHardCeilingTokens() {
        return hardCeilingTokens;
    }

    public void setHardCeilingTokens(int hardCeilingTokens) {
        this.hardCeilingTokens = hardCeilingTokens;
    }

    public int getDefaultMaxInputTokens() {
        return defaultMaxInputTokens;
    }

    public void setDefaultMaxInputTokens(int defaultMaxInputTokens) {
        this.defaultMaxInputTokens = defaultMaxInputTokens;
    }

    public double getDefaultTriggerRatio() {
        return defaultTriggerRatio;
    }

    public void setDefaultTriggerRatio(double defaultTriggerRatio) {
        this.defaultTriggerRatio = defaultTriggerRatio;
    }

    public double getDefaultTargetRatio() {
        return defaultTargetRatio;
    }

    public void setDefaultTargetRatio(double defaultTargetRatio) {
        this.defaultTargetRatio = defaultTargetRatio;
    }

    public double getDefaultReserveRatio() {
        return defaultReserveRatio;
    }

    public void setDefaultReserveRatio(double defaultReserveRatio) {
        this.defaultReserveRatio = defaultReserveRatio;
    }

    public int getDefaultRecentTurns() {
        return defaultRecentTurns;
    }

    public void setDefaultRecentTurns(int defaultRecentTurns) {
        this.defaultRecentTurns = defaultRecentTurns;
    }

    public double getUnknownCapabilityReserveRatio() {
        return unknownCapabilityReserveRatio;
    }

    public void setUnknownCapabilityReserveRatio(double unknownCapabilityReserveRatio) {
        this.unknownCapabilityReserveRatio = unknownCapabilityReserveRatio;
    }

    public double getTierSnipRatio() {
        return tierSnipRatio;
    }

    public void setTierSnipRatio(double tierSnipRatio) {
        this.tierSnipRatio = tierSnipRatio;
    }

    public double getTierSummaryRatio() {
        return tierSummaryRatio;
    }

    public void setTierSummaryRatio(double tierSummaryRatio) {
        this.tierSummaryRatio = tierSummaryRatio;
    }

    public double getMinNewTokensRatio() {
        return minNewTokensRatio;
    }

    public void setMinNewTokensRatio(double minNewTokensRatio) {
        this.minNewTokensRatio = minNewTokensRatio;
    }

    public int getMaxStateChars() {
        return maxStateChars;
    }

    public void setMaxStateChars(int maxStateChars) {
        this.maxStateChars = maxStateChars;
    }

    public int getCheckpointTtlSeconds() {
        return checkpointTtlSeconds;
    }

    public void setCheckpointTtlSeconds(int checkpointTtlSeconds) {
        this.checkpointTtlSeconds = checkpointTtlSeconds;
    }

    public int getSummaryTimeoutMs() {
        return summaryTimeoutMs;
    }

    public void setSummaryTimeoutMs(int summaryTimeoutMs) {
        this.summaryTimeoutMs = summaryTimeoutMs;
    }

    public String getStateHmacKey() {
        return stateHmacKey;
    }

    public void setStateHmacKey(String stateHmacKey) {
        this.stateHmacKey = stateHmacKey == null ? "" : stateHmacKey;
    }

    public RagQuota getRagQuota() {
        return ragQuota;
    }

    public void setRagQuota(RagQuota ragQuota) {
        this.ragQuota = ragQuota == null ? new RagQuota() : ragQuota;
    }

    public Calibration getCalibration() {
        return calibration;
    }

    public void setCalibration(Calibration calibration) {
        this.calibration = calibration == null ? new Calibration() : calibration;
    }

    /**
     * RAG 命中区配额（{@code agent.context.rag-quota.*}，S3-M10）。
     *
     * <p>⚠️ 默认值与 {@code application.yml} 的 {@code ${AGENT_CONTEXT_RAG_QUOTA_*:默认}}
     * 逐字对齐，由 {@code AgentContextPropertiesBindingTest} 用真实 Binder 锁死。
     * 默认条数 8 与 legacy {@code MAX_INJECT_HITS} 相同，是为了灰度切换时**只有 token
     * 口径变化**，不会额外改变命中条数上限；关闭态完全不读这些值。
     */
    public static class RagQuota {
        /** 命中区 token 预算（不含固定围栏 / 根字段；它们由全窗口硬顶兜底）。 */
        private int tokenBudget = 4_096;
        /** 保留正文的命中条数上限（legacy 固定口径为 8）。 */
        private int maxHits = 8;
        /** 单条命中正文的 token 上限（legacy 固定字符口径为 1200）。 */
        private int maxHitTokens = 1_200;
        /** 低于该 token 数的截断块不保留，直接降级引用或丢弃。 */
        private int minKeptTokens = 64;

        public int getTokenBudget() {
            return tokenBudget;
        }

        public void setTokenBudget(int tokenBudget) {
            this.tokenBudget = tokenBudget;
        }

        public int getMaxHits() {
            return maxHits;
        }

        public void setMaxHits(int maxHits) {
            this.maxHits = maxHits;
        }

        public int getMaxHitTokens() {
            return maxHitTokens;
        }

        public void setMaxHitTokens(int maxHitTokens) {
            this.maxHitTokens = maxHitTokens;
        }

        public int getMinKeptTokens() {
            return minKeptTokens;
        }

        public void setMinKeptTokens(int minKeptTokens) {
            this.minKeptTokens = minKeptTokens;
        }
    }

    /** 估算校准（{@code agent.context.calibration.*}）。 */
    public static class Calibration {
        /** 默认 true：影子期就要采集真值/估算误差，但不影响请求。 */
        private boolean enabled = true;
        private int minSamples = 30;
        /** EWMA 平滑系数，与 {@code TokenEstimationCalibrator.DEFAULT_ALPHA} 对齐。 */
        private double alpha = 0.20;
        private double biasClampMin = 0.80;
        private double biasClampMax = 2.00;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int minSamples) {
            this.minSamples = minSamples;
        }

        public double getAlpha() {
            return alpha;
        }

        public void setAlpha(double alpha) {
            this.alpha = alpha;
        }

        public double getBiasClampMin() {
            return biasClampMin;
        }

        public void setBiasClampMin(double biasClampMin) {
            this.biasClampMin = biasClampMin;
        }

        public double getBiasClampMax() {
            return biasClampMax;
        }

        public void setBiasClampMax(double biasClampMax) {
            this.biasClampMax = biasClampMax;
        }
    }
}
