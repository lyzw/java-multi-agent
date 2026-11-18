package cn.studykid.multiagent.ports.agentchat.audit;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.State;

/**
 * 平台级对话审计配置端口（单例配置，前端「系统配置 → 对话审计配置」维护）。
 *
 * <p><b>⚠️ 无缓存</b>：与 {@code EffectiveConfigResolver} 同一工程纪律——
 * 每次读取都查一次 DB，多实例天然一致，前端保存后下一个请求即生效，无需重启 / 清缓存。
 * 调用点在聊天请求入口（每个请求读一次并冻结为快照）。
 *
 * <p><b>⚠️ 缺省回落</b>：DB 无配置行（尚未初始化）时返回
 * {@link ChatAuditConfig#defaults()}（记录开启、双写、全量、fail-open）。
 * 本端口只返回配置内容；<b>是否真正录制</b>由组合根决定：
 * {@code config.enabled() && !yml 应急熔断 && 审计记录器已装配} 三者同时成立才录制。
 * 故 P0 阶段即使 DB 默认为开，未装配记录器时仍零录制。
 *
 * <p><b>⚠️ 不写 outbox_event</b>：配置是读模型，本端口只做单例行的读与整体覆盖写。
 */
public interface ChatAuditConfigPort {

    /**
     * 读取当前生效配置（DB 有行则返回该行，无行返回 {@link ChatAuditConfig#defaults()}）。
     */
    @NonNull State get();

    /**
     * 整体覆盖写单例配置（同键 upsert，版本递增）。
     *
     * @param config  完整配置（前端整表提交，缺字段由校验层补齐默认值）
     * @param actorId 操作者（来自认证上下文，<b>不得</b>取自请求体）
     * @return 写入后的状态（含新版本号）
     */
    @NonNull State save(@NonNull ChatAuditConfig config, @NonNull String actorId);
}
