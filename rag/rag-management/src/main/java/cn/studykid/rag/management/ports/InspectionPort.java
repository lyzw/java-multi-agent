package cn.studykid.rag.management.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.core.InspectionRecord;
import cn.studykid.rag.management.core.InspectionRules;
import cn.studykid.rag.management.core.RagManagementContext;

/**
 * 抽检记录端口（V1.0.2 §5 R2 / 设计 §2.2.3）。
 *
 * <p>⚠️ 只追加不修改：抽检记录是质量判定的证据，改历史记录等于伪造证据。
 */
public interface InspectionPort {

    /**
     * 提交一次抽检（整组记录同事务写入，并回写来源的抽检状态）。
     *
     * <p><b>⚠️ 为什么整组同事务</b>：抽检结论是由一组记录聚合出来的
     * （见 {@link InspectionRules#evaluate}），只写一半记录会让
     * 「结论」与「依据」对不上——而门禁证据包要的正是这两者的一致性。
     *
     * @param context 作用域上下文
     * @param sourceId 来源 ID
     * @param inspectorId 抽检人
     * @param records  记录（须属于该来源）
     * @return 判定结果
     */
    InspectionRules.Verdict submitInspection(@NonNull RagManagementContext context,
                                             @NonNull String sourceId,
                                             @NonNull String inspectorId,
                                             @NonNull List<InspectionRecord> records);

    /**
     * 查询来源的抽检记录（新→旧）。
     *
     * @param context  作用域上下文
     * @param sourceId 来源 ID
     * @param limit    条数上限
     * @return 记录列表
     */
    List<InspectionRecord> listBySource(@NonNull RagManagementContext context,
                                        @NonNull String sourceId,
                                        int limit);


    /**
     * 列出抽检疑点（交互文档 §4.6「看红灯」）。
     *
     * <p><b>它解决什么</b>：抽检台当前把一册教材的上千条片段逐条摆出来裁决
     * （实测 1463 条、翻 159 页），而抽检真正要找的是「哪几条可能出错了」。
     * 本方法由系统按已知的高风险模式（{@link cn.studykid.rag.management.core.SuspicionRules}）
     * 预筛出可疑片段，人只需确认这十几条。
     *
     * <p><b>⚠️ 预筛不参与结论</b>：抽检结论仍由人提交的 {@link InspectionRecord}
     * 聚合得出（{@link InspectionRules#evaluate}）。本方法只决定「先看哪几条」，
     * 故它漏掉的片段不会改变判定结果——但抽检台必须同时提供查看全部的入口。
     *
     * @param context  作用域上下文
     * @param sourceId 来源 ID
     * @param limit    最多返回的疑点数（按严重度排序后截断）
     * @return 疑点列表（HIGH 在前）
     */
    List<cn.studykid.rag.management.core.InspectionSuspicion> listSuspects(
            @NonNull RagManagementContext context,
            @NonNull String sourceId,
            int limit);

    /**
     * 读取来源的当前抽检状态（供 VERIFIED 前置守卫使用）。
     *
     * @param context  作用域上下文
     * @param sourceId 来源 ID
     * @return 抽检状态；来源不存在或无值时返回 null
     */
    String inspectionStatusOf(@NonNull RagManagementContext context, @NonNull String sourceId);
}
