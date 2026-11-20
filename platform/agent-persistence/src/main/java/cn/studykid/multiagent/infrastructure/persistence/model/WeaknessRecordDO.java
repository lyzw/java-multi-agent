package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 薄弱点记录行投影（对应 {@code JdbcProfileQueryRepository.SQL_LOAD_WEAKNESSES}）。
 *
 * <p>⚠️ {@code confidence} 用包装类型 {@link Double}：原实现
 * {@code rs.getDouble(...) + rs.wasNull()} 的语义是「DB NULL → null」，
 * 而 {@code WeaknessView.confidence} 本身声明为 {@code @Nullable}。
 *
 * <p>表结构：{@code weakness_record}（租户表，受 RLS 保护）。
 */
@TableName("weakness_record")
public class WeaknessRecordDO {

    @TableField("tenant_id")
    private @Nullable String tenantId;

    @TableField("knowledge_point")
    private @Nullable String knowledgePoint;

    @TableField("error_type")
    private @Nullable String errorType;

    @TableField("confidence")
    private @Nullable Double confidence;

    @TableField("created_at")
    private @Nullable Instant createdAt;

    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public @Nullable String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(@Nullable String knowledgePoint) {
        this.knowledgePoint = knowledgePoint;
    }
    public @Nullable String getErrorType() { return errorType; }
    public void setErrorType(@Nullable String errorType) { this.errorType = errorType; }
    public @Nullable Double getConfidence() { return confidence; }
    public void setConfidence(@Nullable Double confidence) { this.confidence = confidence; }
    public @Nullable Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(@Nullable Instant createdAt) { this.createdAt = createdAt; }
}
