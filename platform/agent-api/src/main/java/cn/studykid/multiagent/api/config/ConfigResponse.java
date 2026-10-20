package cn.studykid.multiagent.api.config;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/config} 的完整响应。
 *
 * <p><b>⚠️ 绝不暴露任何密钥 / 口令 / 私钥</b>：
 * {@code auth.jwt.private-key}、{@code RABBITMQ_PASSWORD} 等敏感项
 * 一律不出现在任何字段中。仅暴露非敏感的声明值 + 生效标志。
 */
public record ConfigResponse(
        /** 按业务域分组的配置条目。 */
        List<ConfigGroup> groups,
        /** 零保留运行态视图。 */
        ZeroRetentionView zeroRetention,
        /** 扁平的 key → effective 投影，便于前端程序化判断。 */
        Map<String, Boolean> effectiveFlags) {
}
