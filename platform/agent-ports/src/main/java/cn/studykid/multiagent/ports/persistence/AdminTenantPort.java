package cn.studykid.multiagent.ports.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.tenant.TenantRegistry.PrincipalRow;
import cn.studykid.multiagent.core.tenant.TenantRegistry.RlsVerifyResult;
import cn.studykid.multiagent.core.tenant.TenantRegistry.TenantRow;

/**
 * 租户注册表端口（管理控制台 P0.5 · 平台级租户治理）。
 *
 * <p>⚠️ <b>为什么必须有本端口而不能让 API 层直连仓储</b>：
 * {@code tenant} 表是全局表、无 RLS，平台级操作在 DB 层无需 BYPASSRLS ——
 * 这意味着<b>安全性完全依赖应用层的 PLATFORM_ADMIN 作用域闸门</b>。
 * 若 API 层直接持有 JDBC 仓储实现，闸门与数据访问会缠在同一处，
 * 后续任何人新增一个端点都可能「顺手」跳过闸门。
 * 端口把「数据怎么读」与「谁被允许读」彻底分开：
 * 闸门在 API 层，数据访问在基础设施层。
 *
 * <p>⚠️ <b>RLS 敏感表的读取必须显式绑定目标租户</b>：
 * {@code principal} / {@code principal_role} / {@code agent} /
 * {@code tenant_config_override} 均受 RLS 保护。
 * 实现须以<b>目标租户身份</b>读取（而非清空上下文），
 * 否则 RLS 会拒绝一切（返回 0），导致计数失真 ——
 * 那比报错更危险：它会让「本租户有 0 个 Agent」变成看起来正常的假象。
 */
public interface AdminTenantPort {

    /**
     * 列出租户（平台级，全局表）。
     *
     * @param statusFilter 状态过滤；{@code null} 表示不限
     * @param limit        最大返回条数
     * @return 租户行
     */
    @NonNull
    List<TenantRow> listTenants(@Nullable String statusFilter, int limit);

    /**
     * 按标识读取租户。
     *
     * @param tenantId 租户标识
     * @return 租户行；不存在时为 {@code null}
     */
    @Nullable
    TenantRow findTenant(@NonNull String tenantId);

    /**
     * 开通新租户。
     *
     * @param slug 租户短标识
     * @param name 租户显示名
     * @return 新建的租户行
     * @throws IllegalStateException slug 已存在（调用方映射为 409）
     */
    @NonNull
    TenantRow provisionTenant(@NonNull String slug, @NonNull String name);

    /**
     * 变更租户生命周期状态。
     *
     * @param tenantId 租户标识
     * @param status   新状态
     * @return 更新后的租户行；租户不存在时为 {@code null}
     */
    @Nullable
    TenantRow updateStatus(@NonNull String tenantId, @NonNull String status);

    /**
     * 统计租户下的主体数。
     *
     * @param tenantId 租户标识
     * @return 主体数
     */
    long countPrincipals(@NonNull String tenantId);

    /**
     * 统计租户下的 Agent 定义数。
     *
     * @param tenantId 租户标识
     * @return Agent 数
     */
    long countAgentDefs(@NonNull String tenantId);

    /**
     * 统计租户下的配置覆盖数。
     *
     * @param tenantId 租户标识
     * @return 覆盖数
     */
    long countOverrides(@NonNull String tenantId);

    /**
     * 列出租户下的主体及其角色。
     *
     * @param tenantId 租户标识
     * @return 主体行（不含任何凭据）
     */
    @NonNull
    List<PrincipalRow> listPrincipals(@NonNull String tenantId);

    /**
     * RLS 隔离校验：以目标租户身份（非特权）绑定连接后断言其无法读取其他租户数据。
     *
     * @param tenantId 目标租户标识
     * @return 校验结果；{@code leakedRows > 0} 表示隔离被破坏
     */
    @NonNull
    RlsVerifyResult verifyIsolation(@NonNull String tenantId);
}
