package cn.studykid.rag.ports;

/**
 * 版面块类型（解析端口的版面词汇表）。
 *
 * <p><b>⚠️ 为什么枚举的「自引用」是安全的，而「跨 classfile 引用」不安全</b>
 *
 * <p>JDK 21.0.12 的 javac 存在一个可复现的符号解析缺陷：
 * 当某个 <b>classfile</b>（而非源码）持有
 * {@code getstatic cn/studykid/rag/ports/BlockType.XXX} 形式的枚举常量引用时，
 * javac 会为该常量在枚举的成员表里写入一个
 * <b>不带 {@code ACC_ENUM} 标志的占位成员</b>。
 * 枚举常量查找要求该标志，于是后续源码按名引用同一常量时会报
 * {@code 找不到符号 / 位置: 类 BlockType}——<b>即使该 classfile 确实含全部常量</b>。
 *
 * <p><b>规避方式（本项目已固化为规则）</b>：
 * <ol>
 *   <li><b>枚举内部的自引用安全</b>：{@code BlockType.class} 自身 {@code <clinit>}
 *       的 getstatic 发生在该类文件被完整读取的同一次过程中，
 *       真实常量随后进入成员表头部，占位符被覆盖。
 *       因此把「判定语义」写成枚举的实例方法是安全的。</li>
 *   <li><b>跨 classfile 的引用不安全</b>：<b>任何其它 classfile 都不应持有
 *       指向本枚举常量的 getstatic 引用</b>。
 *       因此消费方不得直接写 {@code type == BlockType.HEADING}，
 *       而应调用 {@link #isHeading()} 这类由枚举自身提供的方法。</li>
 * </ol>
 *
 * <p>⚠️ 枚举的 {@code switch} 会生成一个合成类
 * （{@code Outer$1}）持有指向<b>全部</b>常量的 getstatic 引用。
 * javac 读取 classfile 时不会读取方法体，因此该合成类通常不会被加载；
 * 但这依赖实现细节，<b>不得依赖</b>——因此本项目的消费方
 * 一律不直接引用本枚举的常量。
 *
 * <p>复现步骤、规避规则与**实测结论**见 RAG-D09 §6「工具链已知缺陷与规避」。
 * ⚠️ 该节记录的实测结论是：<b>在 JDK 25.0.4 上未能复现</b>（本缺陷记载于 JDK 21.0.12），
 * 因此规避规则 T-1～T-3 是<b>面向 JDK 21.0.x 的防护 + 良好的工程实践</b>，
 * <b>不是</b>「已验证规避有效」。
 */
public enum BlockType {

    /** 标题。 */
    HEADING,

    /** 段落。 */
    PARAGRAPH,

    /** 列表项。 */
    LIST_ITEM,

    /** 表格。 */
    TABLE,

    /** 公式。 */
    FORMULA,

    /** 图形/插图及其题注。 */
    FIGURE,

    /** 题目。 */
    EXERCISE,

    /** 答案/解析条目。 */
    ANSWER,

    /** 页眉页脚/版权页等噪声。 */
    BOILERPLATE;

    /**
     * 是否为标题块。
     *
     * <p>⚠️ 用实例方法而不是 {@code type == BlockType.HEADING}：
     * 后者会让消费方的 classfile 持有指向本枚举常量的 getstatic 引用，
     * 从而触发 JDK 21 javac 的符号解析缺陷（见类注释）。
     * 本方法内部的 {@code this == HEADING} 属于枚举<b>自引用</b>，是安全的。
     *
     * @return 标题块返回 true
     */
    public boolean isHeading() {
        return this == HEADING;
    }

    /**
     * 是否为噪声块（页眉页脚、版权页等）。
     *
     * <p>⚠️ 噪声块<b>不进入片段库</b>——它既不承载知识，也会稀释检索信号。
     *
     * @return 噪声块返回 true
     */
    public boolean isNoise() {
        return this == BOILERPLATE;
    }
}
