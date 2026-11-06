package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.ports.persistence.TenantLifecycleProbe;

/**
 * Worker 侧租户生命周期运行时探针（重规划 §14 / 验收矩阵 #4）。
 *
 * <p>读 {@code public.tenant.status}（由平台生命周期迁移同步的镜像），
 * 供 Worker 在执行前/执行中对租户状态做<b>再验证</b>：
 * 暂停(SUSPENDED)/退役(DECOMMISSIONED/DECOMMISSIONING)/未激活(PENDING_SETUP)
 * 一律拒绝新执行，或在跑任务及时停止。
 *
 * <p>⚠️ <b>{@code public.tenant} 是全局表、无 RLS</b>（见 01_platform_ddl 注释），
 * 故本探针以 Worker 自身连接角色读取即可，<b>不依赖任何租户 RLS 上下文</b>，
 * 也不信任消息体传入的状态（状态以 DB 为准）。
 *
 * <p>⚠️ 纯只读、单条 PK 查询，无副作用；租户不存在即返回 {@link Optional#empty()}，
 * 由调用方 fail-closed 处置（视为不可执行）。
 */
@Repository
public class JdbcTenantLifecycleProbe implements TenantLifecycleProbe {

    private final DataSource dataSource;

    public JdbcTenantLifecycleProbe(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull Optional<String> lifecycleState(@NonNull String tenantId) {
        String sql = "SELECT status FROM public.tenant WHERE tenant_id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(rs.getString("status"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询租户生命周期状态失败: " + tenantId, e);
        }
    }
}
