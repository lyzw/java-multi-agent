package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelDefinition;

/**
 * 统一模型目录仓储（全局表 {@code model_catalog}，<b>无租户、无 RLS</b>）。
 *
 * <p><b>⚠️ 为什么不用 {@code tenantConnection}</b>：模型是平台级资源，
 * 由超级管理员在管理后台统一维护、所有租户共用，<b>不绑定 tenant_id</b>，
 * 表上也没有任何 RLS 策略。故直接走 {@code dataSource.getConnection()}（与
 * {@code JdbcPlatformAgentDirectoryRepository} 同口径），不注入租户上下文。
 *
 * <p><b>⚠️ 不存密钥</b>：本表列集<b>不含任何 api-key 列</b>，
 * 密钥由 {@code ModelCredentialProvider} 按 provider 从环境变量解析。
 *
 * <p><b>⚠️ 事务纪律</b>：手动 {@code setAutoCommit(false)} + commit/rollback，
 * 与持久化模块其它仓储一致；不依赖 Spring 托管事务（全局表、短事务）。
 */
@Repository
public class JdbcModelCatalogRepository implements ModelCatalogPort {

    private final DataSource dataSource;

    public JdbcModelCatalogRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<ModelDefinition> list() {
        String sql = """
                SELECT id, provider, scenario, model_name, display_name, base_url,
                       is_default, supports_tools, supports_streaming, supports_vision,
                       dimension, is_enabled, sort_order, description,
                       created_at, updated_at
                FROM model_catalog
                ORDER BY scenario, sort_order, model_name
                """;
        List<ModelDefinition> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取模型目录失败", e);
        }
        return out;
    }

    @Override
    public @NonNull Optional<ModelDefinition> get(@NonNull String id) {
        String sql = """
                SELECT id, provider, scenario, model_name, display_name, base_url,
                       is_default, supports_tools, supports_streaming, supports_vision,
                       dimension, is_enabled, sort_order, description,
                       created_at, updated_at
                FROM model_catalog WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取模型定义失败: " + id, e);
        }
        return Optional.empty();
    }

    @Override
    public @NonNull ModelDefinition save(@NonNull ModelDefinition definition) {
        String id = definition.id() != null ? definition.id() : UUID.randomUUID().toString();
        String sql = """
                INSERT INTO model_catalog (
                    id, provider, scenario, model_name, display_name, base_url,
                    is_default, supports_tools, supports_streaming, supports_vision,
                    dimension, is_enabled, sort_order, description, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    provider        = EXCLUDED.provider,
                    scenario        = EXCLUDED.scenario,
                    model_name      = EXCLUDED.model_name,
                    display_name    = EXCLUDED.display_name,
                    base_url        = EXCLUDED.base_url,
                    is_default      = EXCLUDED.is_default,
                    supports_tools  = EXCLUDED.supports_tools,
                    supports_streaming = EXCLUDED.supports_streaming,
                    supports_vision = EXCLUDED.supports_vision,
                    dimension       = EXCLUDED.dimension,
                    is_enabled      = EXCLUDED.is_enabled,
                    sort_order      = EXCLUDED.sort_order,
                    description     = EXCLUDED.description,
                    updated_at      = now()
                """; // 注意：created_at 仅在首次插入时取默认 now()，ON CONFLICT 不动它
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, definition.provider());
                ps.setString(3, definition.scenario());
                ps.setString(4, definition.modelName());
                ps.setString(5, definition.displayName());
                ps.setString(6, definition.baseUrl());
                ps.setBoolean(7, definition.isDefault());
                ps.setBoolean(8, definition.supportsTools());
                ps.setBoolean(9, definition.supportsStreaming());
                ps.setBoolean(10, definition.supportsVision());
                if (definition.dimension() != null) {
                    ps.setInt(11, definition.dimension());
                } else {
                    ps.setNull(11, java.sql.Types.INTEGER);
                }
                ps.setBoolean(12, definition.isEnabled());
                ps.setInt(13, definition.sortOrder());
                ps.setString(14, definition.description());
                ps.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("保存模型定义失败: " + definition.modelName(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存模型定义失败", e);
        }
        // 回填主键与最新时间戳（created_at 首次为 now()，updated_at 刚被置为 now()）
        Instant now = Instant.now();
        return new ModelDefinition(id, definition.provider(), definition.scenario(),
                definition.modelName(), definition.displayName(), definition.baseUrl(),
                definition.isDefault(), definition.supportsTools(), definition.supportsStreaming(),
                definition.supportsVision(), definition.dimension(), definition.isEnabled(),
                definition.sortOrder(), definition.description(),
                definition.createdAt() != null ? definition.createdAt() : now, now);
    }

    @Override
    public void delete(@NonNull String id) {
        String sql = "DELETE FROM model_catalog WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除模型定义失败: " + id, e);
        }
    }

    @Override
    public @NonNull Optional<ModelDefinition> defaultForScenario(@NonNull String scenario) {
        String sql = """
                SELECT id, provider, scenario, model_name, display_name, base_url,
                       is_default, supports_tools, supports_streaming, supports_vision,
                       dimension, is_enabled, sort_order, description,
                       created_at, updated_at
                FROM model_catalog
                WHERE scenario = ? AND is_default = true AND is_enabled = true
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scenario);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取场景默认模型失败: " + scenario, e);
        }
        return Optional.empty();
    }

    @Override
    public @NonNull Optional<ModelDefinition> byScenarioAndName(@NonNull String scenario,
                                                              @NonNull String modelName) {
        String sql = """
                SELECT id, provider, scenario, model_name, display_name, base_url,
                       is_default, supports_tools, supports_streaming, supports_vision,
                       dimension, is_enabled, sort_order, description,
                       created_at, updated_at
                FROM model_catalog
                WHERE scenario = ? AND model_name = ? AND is_enabled = true
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scenario);
            ps.setString(2, modelName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "按场景+模型名读取失败: " + scenario + "/" + modelName, e);
        }
        return Optional.empty();
    }

    private static ModelDefinition map(@NonNull ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        int dim = rs.getInt("dimension");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ModelDefinition(
                id,
                rs.getString("provider"),
                rs.getString("scenario"),
                rs.getString("model_name"),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getBoolean("is_default"),
                rs.getBoolean("supports_tools"),
                rs.getBoolean("supports_streaming"),
                rs.getBoolean("supports_vision"),
                rs.wasNull() ? null : dim,
                rs.getBoolean("is_enabled"),
                rs.getInt("sort_order"),
                rs.getString("description"),
                created == null ? null : created.toInstant(),
                updated == null ? null : updated.toInstant());
    }
}
