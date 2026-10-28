package cn.studykid.multiagent.routing;

/**
 * 谓词文法的硬限制（10 号设计 §4.4）。
 *
 * <p>⚠️ <b>超限即拒，绝不降级</b>：
 * 「超了就少校验一点」会把配置缺陷推迟到线上 —— 而这些数字的意义正是
 * <b>最坏成本可预算</b>：规则条数 × 节点数决定了单次委派最坏要花多少 CPU。
 * 允许超预算的规则等于允许一次委派吃掉整个 Worker 的时间片。
 *
 * <p>⚠️ 这些数字<b>不是</b>风格偏好：DNF 积上限直接决定 V4/V5/V6 能否做
 * <b>精确</b>判定（超预算就必须拒发布，而不是退回启发式 ——
 * 一旦允许退回，「策略通过校验」这句话就失去含义）。
 */
public final class PredicateLimits {

    /** 单个谓词的 AST 节点数上限。 */
    public static final int MAX_NODES = 128;

    /** 谓词嵌套深度上限（可读性与栈深度都可预算）。 */
    public static final int MAX_DEPTH = 8;

    /** 正则源码长度上限（与 RE2/J 一起构成防 ReDoS 的两道闸）。 */
    public static final int MAX_REGEX_LENGTH = 128;

    /** 标量数组字面量项数上限。 */
    public static final int MAX_LITERAL_ITEMS = 64;

    /** 单策略的规则条数上限（运行期最坏成本 = 条数 × 节点数）。 */
    public static final int MAX_RULES = 64;

    /** DNF 展开的积上限（超过就必须拒发布，见 {@link #MAX_RULES} 上方的说明）。 */
    public static final int MAX_DNF_CONJUNCTIONS = 4096;

    /**
     * 单个 DNF 析取项（合取式）里允许的字面量数上限。
     *
     * <p>与 {@link #MAX_NODES} 同源：合取式是节点展开出来的，
     * 不设上限时一条「and 套 and」的深规则能让 V5 的两两比对变成平方级。
     */
    public static final int MAX_CONJUNCT_LITERALS = 128;

    private PredicateLimits() {
    }
}
