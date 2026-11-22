package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapToolDefDO;

/**
 * Tool 定义 Mapper（对应 {@code JdbcCapToolDefRepository}）。
 *
 * <p>六条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_FIND_BY_MCP_BINDING}、{@code SQL_LIST_ALL}、
 * {@code SQL_LIST_BY_MCP_SERVER}。SQL 逐字搬迁到 {@code CapToolDefMapper.xml}（仅 {@code ?} → {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}——
 * 尽管 {@code cap_tool_def} 本身是 platform 级表（无 RLS），同一事务内若访问了带 RLS 的表，
 * 缺少上下文会被 RLS 直接拒绝。
 */
public interface CapToolDefMapper extends BaseMapper<CapToolDefDO> {

    /**
     * SQL_INSERT：不可变版本行；{@code ON CONFLICT (id, version) DO NOTHING} 保留原行。
     * 返回受影响行数（0 = 已有同键版本行，适配层映射为 {@code false}）。
     */
    int insertVersion(@Param("id") String id,
                      @Param("version") int version,
                      @Param("name") @Nullable String name,
                      @Param("kind") String kind,
                      @Param("riskLevel") String riskLevel,
                      @Param("mcpServerId") @Nullable String mcpServerId,
                      @Param("mcpToolName") @Nullable String mcpToolName,
                      @Param("httpMethod") @Nullable String httpMethod,
                      @Param("httpUrl") @Nullable String httpUrl,
                      @Param("httpTimeoutMs") @Nullable Integer httpTimeoutMs,
                      @Param("inputSchema") String inputSchema,
                      @Param("paramPolicies") String paramPolicies,
                      @Param("description") @Nullable String description,
                      @Param("configFingerprint") String configFingerprint,
                      @Param("schemaVersion") int schemaVersion,
                      @Param("createdAt") Instant createdAt,
                      @Param("updatedAt") Instant updatedAt,
                      @Param("publishedAt") @Nullable Instant publishedAt);

    /** SQL_FIND：按 id + version 精确读取。 */
    Optional<CapToolDefDO> find(@Param("id") String id, @Param("version") int version);

    /** SQL_FIND_LATEST_PUBLISHED：已发布版本（published_at 非空）中取最高版本。 */
    Optional<CapToolDefDO> findLatestPublished(@Param("id") String id);

    /**
     * SQL_FIND_LATEST：该逻辑 id 的<b>绝对最高版本行</b>（不区分发布状态）。
     *
     * <p>⚠️ 与 SQL_FIND_LATEST_PUBLISHED 的「已发布口径」不同：本方法供版本管理守卫使用 ——
     * 服务端据此算「下一个版本号 = 最高版本 + 1」，避免已有未发布草稿却撞号。
     */
    Optional<CapToolDefDO> findLatest(@Param("id") String id);

    /** SQL_FIND_BY_MCP_BINDING：按 mcp_server_id + mcp_tool_name 反查最新已发布版本。 */
    Optional<CapToolDefDO> findByMcpBinding(@Param("mcpServerId") String mcpServerId,
                                            @Param("mcpToolName") String mcpToolName);

    /** SQL_LIST_ALL：每个逻辑 id 的最高版本整行（DISTINCT ON (id)）。 */
    List<CapToolDefDO> listAll();

    /** SQL_LIST_BY_MCP_SERVER：每个 mcp_tool_name 的最高版本整行（DISTINCT ON (mcp_tool_name)）。 */
    List<CapToolDefDO> listByMcpServer(@Param("mcpServerId") String mcpServerId);

    /** SQL_LIST_VERSIONS：该 id 的全部版本行（不可变历史，按版本倒序 · 能力目录版本回溯用）。 */
    List<CapToolDefDO> listVersions(@Param("id") String id);
}
