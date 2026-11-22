package cn.studykid.multiagent.infrastructure.persistence.cap.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import cn.studykid.multiagent.infrastructure.persistence.cap.model.CapSkillDefDO;

/**
 * Skill 定义 Mapper（对应 {@code JdbcCapSkillDefRepository}）。
 *
 * <p>四条语句与旧 JDBC 常量一一对应：{@code SQL_INSERT}、{@code SQL_FIND}、
 * {@code SQL_FIND_LATEST_PUBLISHED}、{@code SQL_LIST_ALL}。SQL 逐字搬迁到
 * {@code CapSkillDefMapper.xml}（仅 {@code ?} → {@code #{param}}）。
 *
 * <p>⚠️ 本接口由租户通道 SqlSessionFactory 扫描，所有方法都经过
 * {@code RlsTenantBindingInterceptor}：调用前必须由适配层先 {@code TenantScope.bind(...)}——
 * 尽管 {@code cap_skill_def} 本身是 platform 级表（无 RLS），同一事务内若访问了带 RLS 的表，
 * 缺少上下文会被 RLS 直接拒绝。
 */
public interface CapSkillDefMapper extends BaseMapper<CapSkillDefDO> {

    /**
     * SQL_INSERT：不可变版本行；{@code ON CONFLICT (id, version) DO NOTHING} 保留原行。
     * 返回受影响行数（0 = 已有同键版本行，适配层映射为 {@code false}）。
     *
     * <p>⚠️ 参数位次（三级描述改造）：1 id / 2 version / 3 name /
     * 4 description（遗留列，新入库一律 NULL）/ 5 purpose / 6 usage · jsonb /
     * 7 content · text / 8 declared_tools · jsonb / 9 declared_mcp · jsonb /
     * 10 risk_ceiling / 11 param_policies · jsonb / 12 required_caps · jsonb /
     * 13 config_fingerprint / 14 schema_version / 15 created_at / 16 updated_at / 17 published_at。
     * 漏掉内容列之后的 +1 会静默写坏数据（schema_version 写进 published_at…）。
     */
    int insertVersion(@Param("id") String id,
                      @Param("version") int version,
                      @Param("name") @Nullable String name,
                      @Param("description") @Nullable String description,
                      @Param("purpose") @Nullable String purpose,
                      @Param("usage") @Nullable String usage,
                      @Param("content") @Nullable String content,
                      @Param("declaredTools") String declaredTools,
                      @Param("declaredMcp") String declaredMcp,
                      @Param("riskCeiling") String riskCeiling,
                      @Param("paramPolicies") String paramPolicies,
                      @Param("requiredCaps") String requiredCaps,
                      @Param("configFingerprint") String configFingerprint,
                      @Param("schemaVersion") int schemaVersion,
                      @Param("createdAt") Instant createdAt,
                      @Param("updatedAt") Instant updatedAt,
                      @Param("publishedAt") @Nullable Instant publishedAt);

    /** SQL_FIND：按 id + version 精确读取。 */
    Optional<CapSkillDefDO> find(@Param("id") String id, @Param("version") int version);

    /** SQL_FIND_LATEST_PUBLISHED：已发布版本中取最高版本。 */
    Optional<CapSkillDefDO> findLatestPublished(@Param("id") String id);

    /**
     * SQL_FIND_LATEST：该逻辑 id 的<b>绝对最高版本行</b>（不区分发布状态 · 版本管理守卫）。
     *
     * <p>⚠️ 与 SQL_FIND_LATEST_PUBLISHED 的「已发布口径」不同：本方法供版本管理守卫使用 ——
     * 服务端据此算「下一个版本号 = 最高版本 + 1」，避免已有未发布草稿却撞号。
     */
    Optional<CapSkillDefDO> findLatest(@Param("id") String id);

    /** SQL_LIST_ALL：每个逻辑 id 的最高版本整行（DISTINCT ON (id)）。 */
    List<CapSkillDefDO> listAll();

    /** SQL_LIST_VERSIONS：该 id 的全部版本行（不可变历史，按版本倒序 · 能力目录版本回溯用）。 */
    List<CapSkillDefDO> listVersions(@Param("id") String id);
}
