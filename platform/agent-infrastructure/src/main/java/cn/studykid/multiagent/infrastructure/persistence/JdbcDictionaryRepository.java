package cn.studykid.multiagent.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.ports.persistence.DictionaryPort;

/**
 * 平台字典 JDBC 仓储。
 *
 * <p>⚠️ <b>平台域表无 RLS</b>：{@code platform.sys_dictionary*} 由
 * {@code platform_ops} 角色（或超管）管理，应用层以<b>系统身份</b>连接
 * （{@link TenantSessionBinder#clear}），不依赖任何租户身份。
 *
 * <p>⚠️ <b>不物理删除</b>：{@link #setItemEnabled} 只翻 {@code enabled} 位。
 * 字典项一旦被业务行引用，删行会让历史数据出现悬空取值。
 *
 * <p>⚠️ <b>内置行不可删、不可改 code</b>：DB 层不强制这三条（写起来会把
 * 正常维护也挡住），由服务层显式判定并返回 409，便于给出可操作的提示。
 */
@Repository
public class JdbcDictionaryRepository implements DictionaryPort {

    private final DataSource dataSource;

    public JdbcDictionaryRepository(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<DictionaryRow> listDictionaries() {
        String sql = """
                SELECT dict_code, dict_name, description, built_in, enabled, sort_order
                FROM platform.sys_dictionary
                ORDER BY sort_order, dict_code
                """;
        List<DictionaryRow> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapDictionary(rs));
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("列出字典失败", e);
        }
        return out;
    }

    @Override
    public @NonNull Optional<DictionaryRow> findDictionary(@NonNull String dictCode) {
        String sql = """
                SELECT dict_code, dict_name, description, built_in, enabled, sort_order
                FROM platform.sys_dictionary WHERE dict_code = ?
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dictCode);
            try (ResultSet rs = ps.executeQuery()) {
                DictionaryRow row = rs.next() ? mapDictionary(rs) : null;
                conn.commit();
                return Optional.ofNullable(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取字典失败: " + dictCode, e);
        }
    }

    @Override
    public @NonNull List<DictionaryItemRow> listItems(@NonNull String dictCode) {
        String sql = """
                SELECT dict_code, item_code, item_label, sort_order, built_in, enabled
                FROM platform.sys_dictionary_item
                WHERE dict_code = ?
                ORDER BY sort_order, item_code
                """;
        List<DictionaryItemRow> out = new ArrayList<>();
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dictCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapItem(rs));
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("列出字典项失败: " + dictCode, e);
        }
        return out;
    }

    @Override
    public @NonNull Optional<DictionaryItemRow> findItem(
            @NonNull String dictCode, @NonNull String itemCode) {
        String sql = """
                SELECT dict_code, item_code, item_label, sort_order, built_in, enabled
                FROM platform.sys_dictionary_item
                WHERE dict_code = ? AND item_code = ?
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dictCode);
            ps.setString(2, itemCode);
            try (ResultSet rs = ps.executeQuery()) {
                DictionaryItemRow row = rs.next() ? mapItem(rs) : null;
                conn.commit();
                return Optional.ofNullable(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取字典项失败: " + dictCode + "/" + itemCode, e);
        }
    }

    @Override
    public void insertDictionary(@NonNull DictionaryRow row) {
        String sql = """
                INSERT INTO platform.sys_dictionary
                    (dict_code, dict_name, description, built_in, enabled, sort_order)
                VALUES (?, ?, ?, FALSE, ?, ?)
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.dictCode());
            ps.setString(2, row.dictName());
            ps.setString(3, row.description());
            ps.setBoolean(4, row.enabled());
            ps.setInt(5, row.sortOrder());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("新建字典失败: " + row.dictCode(), e);
        }
    }

    @Override
    public void updateDictionary(@NonNull DictionaryRow row) {
        String sql = """
                UPDATE platform.sys_dictionary
                SET dict_name = ?, description = ?, enabled = ?, sort_order = ?,
                    updated_at = now(), version = version + 1
                WHERE dict_code = ?
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.dictName());
            ps.setString(2, row.description());
            ps.setBoolean(3, row.enabled());
            ps.setInt(4, row.sortOrder());
            ps.setString(5, row.dictCode());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("更新字典失败: " + row.dictCode(), e);
        }
    }

    @Override
    public void upsertItem(@NonNull DictionaryItemRow row) {
        String sql = """
                INSERT INTO platform.sys_dictionary_item
                    (dict_code, item_code, item_label, sort_order, built_in, enabled)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (dict_code, item_code) DO UPDATE SET
                    item_label = EXCLUDED.item_label,
                    sort_order = EXCLUDED.sort_order,
                    enabled    = EXCLUDED.enabled,
                    updated_at = now(),
                    version    = platform.sys_dictionary_item.version + 1
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, row.dictCode());
            ps.setString(2, row.itemCode());
            ps.setString(3, row.itemLabel());
            ps.setInt(4, row.sortOrder());
            ps.setBoolean(5, row.builtIn());
            ps.setBoolean(6, row.enabled());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("写入字典项失败: " + row.dictCode() + "/" + row.itemCode(), e);
        }
    }

    @Override
    public void setItemEnabled(@NonNull String dictCode, @NonNull String itemCode, boolean enabled) {
        String sql = """
                UPDATE platform.sys_dictionary_item
                SET enabled = ?, updated_at = now(), version = version + 1
                WHERE dict_code = ? AND item_code = ?
                """;
        try (Connection conn = openSystem();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setString(2, dictCode);
            ps.setString(3, itemCode);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("设置字典项启用状态失败: " + dictCode + "/" + itemCode, e);
        }
    }

    private Connection openSystem() throws SQLException {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        TenantSessionBinder.clear(conn);
        return conn;
    }

    private static DictionaryRow mapDictionary(ResultSet rs) throws SQLException {
        return new DictionaryRow(
                rs.getString("dict_code"),
                rs.getString("dict_name"),
                rs.getString("description"),
                rs.getBoolean("built_in"),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"));
    }

    private static DictionaryItemRow mapItem(ResultSet rs) throws SQLException {
        return new DictionaryItemRow(
                rs.getString("dict_code"),
                rs.getString("item_code"),
                rs.getString("item_label"),
                rs.getInt("sort_order"),
                rs.getBoolean("built_in"),
                rs.getBoolean("enabled"));
    }
}
