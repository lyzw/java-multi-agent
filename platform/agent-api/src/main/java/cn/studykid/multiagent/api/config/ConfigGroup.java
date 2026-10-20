package cn.studykid.multiagent.api.config;

import java.util.List;

/**
 * 配置分组（按业务域聚合若干 {@link ConfigEntry}）。
 *
 * <p>分组只服务于前端展示，不改变判定逻辑。
 */
public record ConfigGroup(
        /** 分组名（如「运行与容量」「工具与审批」）。 */
        String name,
        /** 组内条目。 */
        List<ConfigEntry> entries) {
}
