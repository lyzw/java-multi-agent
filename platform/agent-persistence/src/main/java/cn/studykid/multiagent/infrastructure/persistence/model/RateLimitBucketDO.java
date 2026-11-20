package cn.studykid.multiagent.infrastructure.persistence.model;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/** rate_limit_bucket 表 DO（租户域 + FORCE RLS）。 */
@TableName(value = "rate_limit_bucket", autoResultMap = true)
public class RateLimitBucketDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField("bucket_key")
    private String bucketKey;

    @TableField("window_start")
    private Instant windowStart;

    @TableField("count")
    private long count;

    @TableField("updated_at")
    private Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getBucketKey() { return bucketKey; }
    public void setBucketKey(String v) { this.bucketKey = v; }
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant v) { this.windowStart = v; }
    public long getCount() { return count; }
    public void setCount(long v) { this.count = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
