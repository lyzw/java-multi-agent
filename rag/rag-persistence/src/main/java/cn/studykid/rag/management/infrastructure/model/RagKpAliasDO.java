package cn.studykid.rag.management.infrastructure.model;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识点别名持久化对象（W1 RAG 试点）。表：{@code rag.rag_kp_alias}。
 *
 * <p>⚠️ 主键 alias_id 由 DB 默认 gen_random_uuid() 生成，但本实现与原 JDBC 一致
 * 在应用层先 UUID.randomUUID() 再 INPUT 写入（幂等键是 tenant_id+kp_id+alias_text）。
 */
@TableName("rag.rag_kp_alias")
public class RagKpAliasDO {

    @TableId(value = "alias_id", type = IdType.INPUT)
    private UUID aliasId;

    @TableField("tenant_id")
    private UUID tenantId;

    @TableField("kp_id")
    private UUID kpId;

    @TableField("alias_text")
    private String aliasText;

    @TableField("scope")
    private String scope;

    @TableField("created_at")
    private Instant createdAt;

    public UUID getAliasId() { return aliasId; }
    public void setAliasId(UUID aliasId) { this.aliasId = aliasId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getKpId() { return kpId; }
    public void setKpId(UUID kpId) { this.kpId = kpId; }
    public String getAliasText() { return aliasText; }
    public void setAliasText(String aliasText) { this.aliasText = aliasText; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
