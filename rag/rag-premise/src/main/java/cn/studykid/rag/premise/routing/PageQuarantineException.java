package cn.studykid.rag.premise.routing;

import java.util.List;

import org.jspecify.annotations.NonNull;

/**
 * 疑似扫描件隔离信号（D29 DEC-P1）。
 *
 * <p><b>⚠️ 为什么是异常而不是「返回空文档」</b>：扫描件若被当成文本 PDF 解析，
 * 会得到「看起来成功但内容残缺」的块，进而污染切分与检索。必须 fail-closed：
 * 由编排器捕获本异常，把 job 置为 {@code QUARANTINED_REVIEW} 走人工复核。
 *
 * <p>⚠️ 顶层类型（不嵌套）。
 */
public final class PageQuarantineException extends RuntimeException {

    private final List<Integer> pageNumbers;

    public PageQuarantineException(@NonNull List<Integer> pageNumbers) {
        super("疑似扫描件页需隔离复核（不静默当文本 PDF 处理）: " + pageNumbers);
        this.pageNumbers = List.copyOf(pageNumbers);
    }

    /** @return 需要隔离的 1 起物理页码 */
    public @NonNull List<Integer> pageNumbers() {
        return pageNumbers;
    }
}
