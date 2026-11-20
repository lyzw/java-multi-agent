package cn.studykid.multiagent.infrastructure.persistence.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import cn.studykid.multiagent.infrastructure.persistence.cap.JsonbTypeHandler;

/**
 * Run 输入读取持久化对象（对应 {@code JdbcRunInputRepository} 的 SQL_FIND_INPUT）。
 *
 * <p>⚠️ persistence 模块<b>内部</b> DO，不与任何 core record 混用；
 * 适配层 {@code MybatisRunInputRepository} 负责 DO → Map 的转换（{@code parse()} 语义逐字保留）。
 *
 * <p>表结构：{@code run}。SQL 只读 {@code tenant_id} 与 {@code input_payload::text} 两列。
 * {@code input_payload} 为 JSONB 列，复用 {@link JsonbTypeHandler} 按 String 读取，
 * SQL 侧保留显式 {@code ::text} cast（与旧 JDBC 逐字一致）。
 */
@TableName("run")
public class RunInputDO {

    @TableField("tenant_id")
    private String tenantId;

    @TableField(value = "input_payload", typeHandler = JsonbTypeHandler.class)
    private String inputPayload;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getInputPayload() { return inputPayload; }
    public void setInputPayload(String inputPayload) { this.inputPayload = inputPayload; }
}
