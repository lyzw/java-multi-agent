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
     * 列知识点摘要：租户集合 ANY + ILIKE 模糊匹配。
     *
     * <p>⚠️ 2026-11-21 H1 修复：旧版用 {@code tenantLiteral} 原样拼接 SQL 字符串，
     * 一旦上游 {@code context.queryTenantId()} 被污染（JWT claim 伪造 / 未校验），
     * 就会变成 SQL 注入入口。改为接收 {@code List<UUID>}，MyBatis 以 JDBC 参数形式
     * 传入 {@code ANY(#{tenantUuids}::uuid[])}，PG 自动做数组类型绑定，
     * 彻底消除动态字符串拼装。
     */
    List<KnowledgePointBrief> listKnowledgePoints(@Param("tenantUuids") List<UUID> tenantUuids,
                                                   @Param("keyword") String keyword,
                                                   @Param("limit") int limit);
}
