package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapMcpServerDef;

/**
 * MCP Server 定义版本行读取端口（§6.1 {@code cap_mcp_server_def}）。
 *
 * <p>platform 级对象（无 RLS）。
 */
public interface CapMcpServerDefPort {

    /**
     * 按固定版本读取。
     *
     * @param serverId MCP Server 逻辑 id
     * @param version  版本号
     * @return 版本行；不存在时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapMcpServerDef> find(@NonNull String serverId, int version);

    /**
     * 读取该 Server 的<b>最新已发布</b>版本行。
     *
     * <p>⚠️ 同 {@link CapToolDefPort#findLatestPublished}：仅用于诊断与迁移。
     *
     * @param serverId MCP Server 逻辑 id
     * @return 最新已发布版本行；无已发布版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapMcpServerDef> findLatestPublished(@NonNull String serverId);
}
