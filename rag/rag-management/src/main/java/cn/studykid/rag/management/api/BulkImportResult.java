package cn.studykid.rag.management.api;

import java.util.List;

/**
 * 批量导入结果（V1.0.2 §4 Z10 / S3-5）。
 *
 * <p><b>⚠️ 逐条明细是必需的，不是可选的</b>：批量导入最容易出现「99 条成功、1 条失败」，
 * 若只回一个总数，采集员无从知道哪一条出了问题——最后只能整批重传，
 * 而整批重传又会撞上去重预检。
 *
 * @param total    清单总行数
 * @param accepted 成功受理的条数
 * @param rejected 失败的条数
 * @param items    逐条明细
 * @param extras   ZIP 内存在但清单未列的文件（已忽略）
 */
public record BulkImportResult(
        int total,
        int accepted,
        int rejected,
        List<Item> items,
        List<String> extras
) {

    /**
     * 单条明细。
     *
     * @param lineNo     清单行号
     * @param filename   文件名
     * @param accepted   是否成功
     * @param documentId 成功时的载体 ID
     * @param jobId      成功时的摄取任务 ID（可为 null：job 端口未装配）
     * @param reason     失败原因
     */
    public record Item(
            int lineNo,
            String filename,
            boolean accepted,
            String documentId,
            String jobId,
            String reason
    ) {
    }
}
