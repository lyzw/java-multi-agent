package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 版面块（解析端口输出的最小版面单元）。
 *
 * <p><b>⚠️ 为什么是顶层类型而不是 {@code DocumentParserPort} 的嵌套 record</b>
 *
 * <p>JDK 21.0.12 的 javac 存在一个可复现的符号解析缺陷：
 * 当一个编译单元<b>同时导入</b>某个外层类型 {@code X} 与 <b>其嵌套类型</b>
 * {@code X.Nested} 时，javac 会提前「完成」{@code X}；
 * 若 {@code X} 的嵌套类型持有某个枚举类型的字段，
 * 该枚举会被登记为<b>仅读取了类文件头部</b>的依赖符号——
 * <b>成员表始终为空</b>，于是源码里再引用它的常量就会报
 * {@code 找不到符号 / 位置: 类 XxxEnum}。
 *
 * <p>本类型提升为顶层后不再位于任何外层类型的内部，
 * 枚举 {@link BlockType} 也就不会被外层接口「顺带」拖入依赖链。
 * 该规则由 {@code NestedTypeImportGuardTest} 强制执行（见 RAG-D09 §6）。
 *
 * <p>⚠️ 该守卫测试此前<b>只存在于本段 Javadoc 里、实际并不存在</b>——
 * 即「规则声称有守卫、实际无守卫」。已由 RAG-D09 片补齐
 * （{@code rag-ports/src/test/java/cn/studykid/rag/ports/NestedTypeImportGuardTest.java}）。
 *
 * @param type 块类型
 * @param text 文本内容
 * @param pageNumber 物理页码
 * @param headingLevel 标题层级（1 表示章，0 表示非标题）
 * @param sectionPath 所属章节路径
 * @param exerciseNo 识别出的题号（非题目块为 null）
 * @param inAnswerZone 是否位于答案区（版面级判定，非模型判定）
 * @param charStart 该页字符流起始偏移
 * @param charEnd 结束偏移
 */
public record LayoutBlock(
        @NonNull BlockType type,
        @NonNull String text,
        int pageNumber,
        int headingLevel,
        @NonNull String sectionPath,
        @Nullable String exerciseNo,
        boolean inAnswerZone,
        int charStart,
        int charEnd
) {

    public LayoutBlock {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (text == null) {
            throw new IllegalArgumentException("text 不能为空");
        }
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber 必须 >= 1");
        }
        if (sectionPath == null) {
            throw new IllegalArgumentException("sectionPath 不能为空");
        }
    }
}
