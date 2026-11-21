package cn.studykid.rag.management.infrastructure.mapper;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.rag.management.infrastructure.model.RagKpAliasDO;
import cn.studykid.rag.management.ports.KnowledgeAliasPort.KnowledgePointBrief;

/**
 * 知识点别名 Mapper（W1 RAG 试点）。
 *
 * <p>⚠️ 本接口由 ragTenantSqlSessionFactory 扫描，所有方法经 RagRlsBindingInterceptor。
 */
public interface RagKpAliasMapper extends BaseMapper<RagKpAliasDO> {

    /** 幂等增补：ON CONFLICT DO NOTHING，返回实际新增条数（0 = 已存在）。 */
    int insertIfAbsent(@Param("aliasId") UUID aliasId,
                       @Param("tenantId") UUID tenantId,
                       @Param("kpId") UUID kpId,
                       @Param("aliasText") String aliasText);

    /** 列出别名（显式 tenant_id 谓词 + 全序排序，与原 JDBC 逐字一致）。 */
    List<String> listAliases(@Param("tenantId") UUID tenantId, @Param("kpId") UUID kpId);

    /** 删除别名。 */
    int removeAlias(@Param("tenantId") UUID tenantId,
                    @Param("kpId") UUID kpId,
                    @Param("aliasText") String aliasText);

    /**
     * 列知识点摘要：动态 tenant 字面量 + rag_global_tenant_id() + ILIKE 模糊匹配。
     *
     * <p>⚠️ tenantLiteral 由应用层从可信 context.queryTenantId() 生成（非客户端输入），
     * 与原 JDBC 的 {@code .replace("{rag_tenant}", tenantLiteral)} 同口径。
     */
    List<KnowledgePointBrief> listKnowledgePoints(@Param("tenantLiteral") String tenantLiteral,
                                                   @Param("keyword") String keyword,
                                                   @Param("limit") int limit);
}
