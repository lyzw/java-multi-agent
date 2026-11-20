package cn.studykid.multiagent.infrastructure.persistence.cap;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * JSONB 字段 TypeHandler（POC，对应主文档 §4.1 / §8.3）。
 *
 * <p>把 DO 里的 {@code String} 与 PG {@code jsonb} 双向映射。
 * 对应 JPA 的 {@code AttributeConverter}——但 MyBatis 需要显式注册到
 * {@code @TableField(typeHandler=...)} 与 XML 的 {@code #{..., typeHandler=...}}。
 *
 * <p>⚠️ SQL 侧仍保留显式 {@code ::jsonb} cast（与现有 JDBC 实现逐字一致）：
 * {@code VALUES (?, ?, ?::jsonb, ...)}。PG 驱动把 String 作为 varchar 传入，
 * cast 负责转成 jsonb；读侧 {@code draft_config::text} 后由本 Handler 收 String。
 *
 * <p>⚠️ 空串/空白按『空 JSON 数组』处理要与原实现口径一致——
 * 本项目 cap_config_draft.failure_details 默认 {@code '[]'}，不是 null。
 */
@MappedTypes(String.class)
@MappedJdbcTypes({JdbcType.OTHER, JdbcType.VARCHAR})
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter); // 配合 SQL 中的 ::jsonb cast
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName); // 配合 SELECT ... col::text
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
