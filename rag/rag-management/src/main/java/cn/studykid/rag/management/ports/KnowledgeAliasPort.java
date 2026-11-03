package cn.studykid.rag.management.ports;

import java.util.List;

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
}
