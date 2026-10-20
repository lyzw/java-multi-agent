package cn.studykid.rag.ports;

import org.jspecify.annotations.NonNull;

/**
 * 语料解析端口（RAG-D02 §3.4 / RAG-D04 §3）。
 *
 * <p><b>⚠️ 解析质量是教辅 RAG 的<b>第一瓶颈</b>，比检索算法更关键</b>。
 * 检索算法再好，解析时把公式 OCR 成乱码、把题号丢掉、
 * 把答案区和正文混在一起，召回就必然出错。
 *
 * <p>因此本端口的输出不是「一坨纯文本」，而是<b>带版面结构的块序列</b>：
 * 标题层级、题号、答案区标识、页码都必须保留。
 *
 * <p><b>实现建议</b>：版面感知的解析器（如 Docling，产出带版面模型的
 * 标题/段落/表格/公式树）优于纯文本抽取器（如 Apache Tika）——
 * 后者的输出是「纯文本或 XHTML」，<b>不重建表格与阅读顺序</b>，
 * 对密集排版的教辅 PDF 需要额外补一层版面解析。
 *
 * <p><b>⚠️ 本接口刻意保持「瘦 SPI」：只声明方法，不持有任何嵌套类型</b>。
 * 词汇表（{@link BlockType}、{@link LayoutBlock}、{@link ParsedDocument}）
 * 一律声明为顶层类型。
 *
 * <p>原因不是审美，而是 <b>JDK 21.0.12 的 javac 存在可复现的符号解析缺陷</b>：
 * 编译单元若<b>同时导入</b>外层类型与其嵌套类型，javac 会提前完成外层类型；
 * 外层类型内嵌套类型所持有的枚举字段会让该枚举被登记为
 * 「仅读类文件头部」的依赖符号，<b>成员表为空</b>，
 * 随后源码引用该枚举常量即报 {@code 找不到符号}。
 * 词汇表全部顶层化后，该触发链不存在。
 *
 * <p>详见 {@link BlockType} 与 {@link LayoutBlock} 的类注释，
 * 以及 RAG-D09 §6「工具链已知缺陷与规避」。
 */
public interface DocumentParserPort {

    /**
     * 解析文档为结构化块序列。
     *
     * @param documentId 文档 ID
     * @param content 文档内容（PDF 字节流或文本）
     * @return 结构化块
     */
    @NonNull ParsedDocument parse(@NonNull String documentId, @NonNull byte[] content);
}
