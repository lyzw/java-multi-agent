package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapMcpServerDefDO;

/**
 * MCP Server 定义 Mapper（对应 {@code JdbcCapMcpServerDefRepository}）。
 *
 * <p>四条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_LIST_ALL}。SQL 逐字搬迁到
 * {@code CapMcpServerDefMapper.xml}（仅 {@code ?} → {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}——
 * 尽管 {@code cap_mcp_server_def} 本身是 platform 级表（无 RLS），同一事务内若访问了带 RLS 的表，
 * 缺少上下文会被 RLS 直接拒绝。
 */
public interface CapMcpServerDefMapper extends BaseMapper<CapMcpServerDefDO> {

    /**
     * SQL_INSERT：不可变版本行；{@code ON CONFLICT (id, version) DO NOTHING} 保留原行。
     * 返回受影响行数（0 = 已有同键版本行，适配层映射为 {@code false}）。
     *
     * <p>{@code exposedToolAllowlist} 为 null 时传 null（未配置，与显式 {@code []} 语义不同，§4.4）。
     */
    int insertVersion(@Param("id") String id,
                      @Param("version") int version,
                      @Param("transport") String transport,
                      @Param("endpoint") @Nullable String endpoint,
                      @Param("command") @Nullable String command,
                      @Param("authKind") String authKind,
                      @Param("trustTier") String trustTier,
                      @Param("exposedToolAllowlist") @Nullable String exposedToolAllowlist,
                      @Param("connectionRisk") String connectionRisk,
                      @Param("dynamicDiscovery") boolean dynamicDiscovery,
                      @Param("configFingerprint") String configFingerprint,
                      @Param("credentialRef") @Nullable String credentialRef,
                      @Param("schemaVersion") int schemaVersion,
                      @Param("createdAt") Instant createdAt,
                      @Param("updatedAt") Instant updatedAt,
                      @Param("publishedAt") @Nullable Instant publishedAt);

    /** SQL_FIND：按 id + version 精确读取。 */
    Optional<CapMcpServerDefDO> find(@Param("id") String id, @Param("version") int version);

    /** SQL_FIND_LATEST_PUBLISHED：已发布版本中取最高版本。 */
    Optional<CapMcpServerDefDO> findLatestPublished(@Param("id") String id);

    /**
     * SQL_FIND_LATEST：该逻辑 id 的<b>绝对最高版本行</b>（不区分发布状态 · 版本管理守卫）。
     *
     * <p>⚠️ 与 SQL_FIND_LATEST_PUBLISHED 的「已发布口径」不同：本方法供版本管理守卫使用 ——
     * 服务端据此算「下一个版本号 = 最高版本 + 1」，避免已有未发布草稿却撞号。
     */
    Optional<CapMcpServerDefDO> findLatest(@Param("id") String id);

    /** SQL_PUBLISH_DRAFT：把未发布草稿行转正；返回影响行数（0 = 无草稿可转正）。 */
    int publishDraft(@Param("id") String id,
                     @Param("version") int version,
                     @Param("publishedAt") Instant publishedAt);

    /** SQL_UPDATE_DRAFT：仅更新未发布草稿行；返回影响行数（0 = 不存在或已发布）。 */
    int updateDraft(@Param("id") String id,
                    @Param("version") int version,
                    @Param("transport") String transport,
                    @Param("endpoint") @Nullable String endpoint,
                    @Param("command") @Nullable String command,
                    @Param("authKind") String authKind,
                    @Param("trustTier") String trustTier,
                    @Param("exposedToolAllowlist") @Nullable String exposedToolAllowlist,
                    @Param("connectionRisk") String connectionRisk,
                    @Param("dynamicDiscovery") boolean dynamicDiscovery,
                    @Param("configFingerprint") String configFingerprint,
                    @Param("credentialRef") @Nullable String credentialRef,
                    @Param("updatedAt") Instant updatedAt);

    /** SQL_LIST_ALL：各逻辑 id 的最高版本行，按 id 升序。 */
    List<CapMcpServerDefDO> listAll();

    /** SQL_LIST_VERSIONS：该 id 的全部版本行（不可变历史，按版本倒序 · 能力目录版本回溯用）。 */
    List<CapMcpServerDefDO> listVersions(@Param("id") String id);
}
