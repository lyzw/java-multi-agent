package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * 对话审计原文的保留期清理端口（PIPL §7「保留期」）。
 *
 * <p><b>⚠️ 为什么审计插件必须有这个端口</b>：
 * {@code deploy/sql/platform/05_chat_audit.sql} 建了一张存<b>完整原文</b>的表
 * （用户问题、历史、模型回答、thinking、逐分片、RAG 命中、工具入参与返回），
 * 而「原文」恰恰是 PIPL 里保留期约束最严的一类数据。
 * 只写入不清理的后果不是「多占磁盘」，而是
 * <b>「保留 30 天」这个承诺只存在于配置页面上</b>——
 * 页面上写着 30 天，库里无限期留存，且系统没有任何信号。
 *
 * <p><b>⚠️ 为什么单独一个端口，不并进 {@code ErasurePort}</b>：
 * 两者的保留期<b>来源不同</b>——{@code ErasurePort} 的窗口来自 yml
 * （{@code RetentionSettings}，运维口径），本端口来自
 * <b>前端可改的 {@code retention.days}</b>（产品口径，默认 30 天与 message-days 对齐）。
 * 合在一起会出现「页面写 7 天、实际按 yml 的 90 天删」这种
 * 无法从任何单一位置解释的偏差。
 *
 * <p><b>⚠️ 必须由 {@code agent_retention} 凭据执行</b>：
 * {@code 03_platform_roles.sql} 只把 {@code chat_audit_record} 的
 * {@code SELECT, INSERT} 给了应用角色并 {@code REVOKE DELETE}；
 * 用应用凭据跑清理只会在到期那天得到 {@code permission denied}。
 */
public interface ChatAuditRetentionPort {

    /**
     * 删除某租户在 cutoff 之前创建的审计记录。
     *
     * <p>⚠️ <b>必须带 {@code tenantId}</b>：本端口刻意不提供「不带租户删全表」的方法
     * （与 {@link cn.studykid.multiagent.ports.persistence.ErasurePort} 同一纪律）。
     * 表上 {@code FORCE ROW LEVEL SECURITY} + 租户谓词意味着
     * 若不绑定租户上下文，本方法会<b>静默删 0 行</b>——那种「清理成功但一行没删」
     * 的假绿比报错更难发现。
     *
     * @param tenantId 租户标识，必填
     * @param cutoff   早于该时点 {@code created_at} 的记录视为到期
     * @param limit    单轮上限（防止一个长事务锁住整张表）
     * @return 实际删除的行数
     */
    int purgeExpired(@NonNull String tenantId,
                     @NonNull Instant cutoff,
                     int limit);
}
