package cn.studykid.rag.premise.model;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.ports.BlockType;

/**
 * 页级路由信号（D27 §4.1 判定信号 / D28 P1-1 组件契约）。
 *
 * <p><b>⚠️ 信号来源</b>：由轻量通道（primary 解析后端）的<b>已解析块</b>推导，
 * 不再二次解析 PDF 字节——这是「先用便宜路径、复杂页升级」的落点：
 * 轻量通道本就要跑一遍，路由只在其产物上统计，额外成本为零。
 *
 * <p><b>⚠️ 置信度口径</b>：{@link PremiseBlock#confidence()} 未提供时为 {@code NaN}。
 * 本类型把它转成 {@code hasConfidence}/{@code meanConfidence}/{@code unknownConfidenceRatio}
 * 三个明确字段，<b>不得</b>用 0 或 1 冒充「未知」（会把低置信页误判成干净页）。
 *
 * <p>⚠️ 顶层类型（不嵌套）。
 *
 * @param pageNumber             1 起物理页码
 * @param blockCount             该页块总数
 * @param textCharCount          文本字符数（所有块 text 长度之和）
 * @param figureCount            FIGURE 块数（图像占比信号）
 * @param tableCount             TABLE 块数
 * @param formulaCount           FORMULA 块数
 * @param meanConfidence         已知置信度的均值；全未知为 {@code Double.NaN}
 * @param unknownConfidenceRatio 无置信度块 / 总块数（空页为 0）
 */
public record PageSignals(
        int pageNumber,
        int blockCount,
        int textCharCount,
        int figureCount,
        int tableCount,
        int formulaCount,
        double meanConfidence,
        double unknownConfidenceRatio) {

    public PageSignals {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber 必须 >= 1（1 起物理页码）");
        }
        if (blockCount < 0 || textCharCount < 0
                || figureCount < 0 || tableCount < 0 || formulaCount < 0) {
            throw new IllegalArgumentException("计数字段必须 >= 0");
        }
        if (unknownConfidenceRatio < 0.0 || unknownConfidenceRatio > 1.0
                || Double.isNaN(unknownConfidenceRatio)) {
            throw new IllegalArgumentException("unknownConfidenceRatio 必须在 [0,1]");
        }
    }

    /**
     * 从一页的归一化块推导信号（纯函数，可单测）。
     *
     * @param pageNumber 1 起物理页码
     * @param blocks     该页的块（允许为空——空页是扫描件判定的关键信号）
     * @return 页级信号
     */
    public static @NonNull PageSignals of(int pageNumber, @NonNull List<PremiseBlock> blocks) {
        if (blocks == null) {
            throw new IllegalArgumentException("blocks 不能为空");
        }
        int figures = 0;
        int tables = 0;
        int formulas = 0;
        int chars = 0;
        int known = 0;
        double confSum = 0.0;
        for (PremiseBlock block : blocks) {
            BlockType type = block.type();
            if (type == BlockType.FIGURE) {
                figures++;
            } else if (type == BlockType.TABLE) {
                tables++;
            } else if (type == BlockType.FORMULA) {
                formulas++;
            }
            String text = block.text();
            if (text != null) {
                chars += text.length();
            }
            if (block.hasConfidence()) {
                confSum += block.confidence();
                known++;
            }
        }
        int total = blocks.size();
        double mean = known == 0 ? Double.NaN : confSum / known;
        double unknownRatio = total == 0 ? 0.0 : (double) (total - known) / total;
        return new PageSignals(pageNumber, total, chars, figures, tables, formulas,
                mean, unknownRatio);
    }

    /** @return 是否提供了置信度（全未知时为 false） */
    public boolean hasConfidence() {
        return !Double.isNaN(meanConfidence);
    }

    /** @return 复杂块（表格/公式/图）总数 */
    public int complexBlockCount() {
        return figureCount + tableCount + formulaCount;
    }
}
