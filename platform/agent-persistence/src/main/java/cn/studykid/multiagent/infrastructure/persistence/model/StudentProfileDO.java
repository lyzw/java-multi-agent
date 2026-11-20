package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import org.jspecify.annotations.Nullable;

/**
 * 学习画像行投影（对应 {@code JdbcProfileQueryRepository.SQL_LOAD_PROFILE}）。
 *
 * <p>⚠️ <b>白名单纪律</b>：只承载 {@code tenant_id} / {@code grade} / {@code subject} /
 * {@code updated_at} 四列 —— 姓名、学校、班级等列在 DB 层也不存在（V2 迁移刻意不建）。
 *
 * <p>⚠️ {@code tenant_id} 以 String 承载（原实现 {@code rs.getString("tenant_id")}），
 * 仅用于影子染色的行归属比对，不参与业务返回。
 *
 * <p>表结构：{@code student_profile}（租户表，受 RLS 保护）。
 */
@TableName("student_profile")
public class StudentProfileDO {

    @TableField("tenant_id")
    private @Nullable String tenantId;

    @TableField("grade")
    private @Nullable String grade;

    @TableField("subject")
    private @Nullable String subject;

    @TableField("updated_at")
    private @Nullable Instant updatedAt;

    public @Nullable String getTenantId() { return tenantId; }
    public void setTenantId(@Nullable String tenantId) { this.tenantId = tenantId; }
    public @Nullable String getGrade() { return grade; }
    public void setGrade(@Nullable String grade) { this.grade = grade; }
    public @Nullable String getSubject() { return subject; }
    public void setSubject(@Nullable String subject) { this.subject = subject; }
    public @Nullable Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(@Nullable Instant updatedAt) { this.updatedAt = updatedAt; }
}
