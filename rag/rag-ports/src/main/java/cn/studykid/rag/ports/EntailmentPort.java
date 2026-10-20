package cn.studykid.rag.ports;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.rag.core.citation.Citation;
import cn.studykid.rag.core.grounding.GroundingVerdict;

/**
 * 接地判定端口（RAG-D02 §3.3 / RAG-D06 §4）。
 *
 * <p><b>⚠️ 为什么蕴含判定必须是独立能力而不是「再问模型一次」</b>：
 * 让生成模型自己判断「我这段话有没有依据」在工程上不可靠——
 * 同一个模型既是被审者又是审者，倾向<b>放行自己的输出</b>。
 * 这与主平台 P1-D08 R-016（同基座模型盲区）是<b>同一个问题</b>。
 *
 * <p>因此本端口默认实现应使用<b>与生成模型不同的模型或不同判别式</b>
 * （NLI 蕴含模型 / 判别式重排器 / 规则+词重叠的保守基线），
 * 并把判定结果纳入评测集回归。
 */
public interface EntailmentPort {

    /**
     * 判定「声明」是否被「证据」蕴含。
     *
     * @param claim 待判定的声明（回答中的一句）
     * @param evidence 支持该声明的语料片段全文
     * @return 蕴含度（0~1）
     */
    double entailment(@NonNull String claim, @NonNull String evidence);

    /**
     * 批量判定并产出逐条裁决。
     *
     * @param claims 声明列表
     * @param candidates 每条声明的候选引用
     * @return 逐条裁决
     */
    @NonNull List<GroundingVerdict> judge(@NonNull List<String> claims,
                                           @NonNull Map<String, List<Citation>> candidates);

    /**
     * 校验引用是否真实存在于语料（存在性校验）。
     *
     * <p>⚠️ 这是<b>第一道</b>接地检查，且它<b>不依赖模型</b>——
     * 纯粹是「chunkId 是否存在 + 版本是否匹配 + 引文是否为子串」。
     * 判定成本极低且结论确定，应<b>先于</b>模型判定执行。
     *
     * @param citations 待校验引用
     * @return 是否全部存在
     */
    boolean verifyExistence(@NonNull List<Citation> citations);

    /**
     * 检测引用之间的<b>事实冲突</b>。
     *
     * <p>例如：人教版说「该定理成立」，苏教版说「需附加条件」。
     * 这类冲突不应被静默择一。
     *
     * @param citations 引用集合
     * @return 冲突描述；无冲突返回 {@code null}
     */
    @Nullable String detectConflict(@NonNull List<Citation> citations);
}
