package cn.studykid.rag.management.ports;

import java.util.List;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.management.core.RagManagementContext;

/**
 * 知识点别名端口（V1.0.2 §5 R7 / §8.3 / 设计 §2.2.3）。
 *
 * <p><b>⚠️ 本端口服务于「已存在的匹配机制」，不是新建匹配逻辑</b>：
 * {@code KnowledgePoint} 这个 record 早已内置 {@code aliases} 列表与
 * {@code matches(normalizedText)} 别名匹配。本表与端口的作用是
 * <b>为那套内存匹配提供持久化载体与人工维护入口</b>——
 * 教研在这里增补别名，摄取时把它一并加载进 {@code KnowledgePoint.aliases}。
 *
 * <p><b>⚠️ 不复用 {@code rag_dictionary}</b>：后者是通用 {@code dict_type/code}
 * 词表，无法表达「某 kp_id 的别名集合」这一关系（见 §8.3 的定调）。
 */
public interface KnowledgeAliasPort {

    /**
     * 增补别名。
     *
     * <p>⚠️ 幂等：同一知识点下同一别名重复提交不报错也不重复插入
     * （{@code rag_kp_alias_unique} 约束 + ON CONFLICT DO NOTHING）。
     *
     * @param context  作用域上下文
     * @param kpId     知识点 ID
     * @param alias    别名文本
     * @return 实际新增的条数（0 表示已存在）
     */
    int addAlias(RagManagementContext context, String kpId, String alias);

    /**
     * 列出某知识点的别名。
     *
     * @param context 作用域上下文
     * @param kpId    知识点 ID
     * @return 别名列表
     */
    List<String> listAliases(RagManagementContext context, String kpId);

    /**
     * 删除别名。
     *
     * <p>⚠️ 允许删除：别名是「人工维护的提示词」，不像抽检记录那样是证据；
     * 维护错了必须能撤销，否则错别名会永久污染挂接。
     *
     * @param context 作用域上下文
     * @param kpId    知识点 ID
     * @param alias   别名文本
     * @return 实际删除条数
     */
    int removeAlias(RagManagementContext context, String kpId, String alias);

    /**
     * 列出知识点本体（RAG-D15 §六 待办 #8）。
     *
     * <p><b>⚠️ 为什么需要它</b>：改造前只有 {@code listAliases(kpId)}——
     * 调用方必须<b>先知道 kpId</b> 才能查别名，而管理台无从得知有哪些知识点。
     * 别名页因此只能做成"粘贴 UUID"，教研根本用不了。
     *
     * <p>⚠️ 只返回管理需要的最小字段（id/学科/年级/规范名/先修数量），
     * 不返回 facets 等大字段——列表页不需要，传了只是浪费带宽。
     *
     * @param context 作用域上下文
     * @param keyword 按规范名模糊匹配（可空 = 全部）
     * @param limit 条数上限
     * @return 知识点摘要列表（按学科/年级/规范名排序）
     */
    @NonNull List<KnowledgePointBrief> listKnowledgePoints(@NonNull RagManagementContext context,
                                                           String keyword,
                                                           int limit);

    /**
     * 知识点摘要。
     *
     * @param kpId 知识点 ID
     * @param subject 学科
     * @param grade 年级
     * @param canonicalName 规范名
     * @param aliasCount 已有别名数量
     * @param prerequisiteCount 先修关系数量
     */
    record KnowledgePointBrief(
            @NonNull String kpId,
            @NonNull String subject,
            @NonNull String grade,
            @NonNull String canonicalName,
            int aliasCount,
            int prerequisiteCount
    ) {
    }
}
