package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapToolDef;

/**
 * Tool 定义版本行读取端口（§6.1 {@code cap_tool_def}）。
 *
 * <p>platform 级对象（无 RLS），读取不受租户边界限制。
 */
public interface CapToolDefPort {

    /**
     * 按固定版本读取。
     *
     * @param toolId  Tool 逻辑 id
     * @param version 版本号
     * @return 版本行；不存在时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapToolDef> find(@NonNull String toolId, int version);

    /**
     * 读取该 Tool 的<b>最新已发布</b>版本行。
     *
     * <p>⚠️ 该口径<b>不等价于</b>「当前发布所指版本」（§6.3）：
     * 回滚发布后，最新已发布行可能是已回滚的版本。
     * 因此该方法仅用于<b>诊断与迁移</b>，
     * <b>不得</b>用于授权判定中的版本解析（那必须走 {@link PublishedVersionPort}）。
     *
     * @param toolId Tool 逻辑 id
     * @return 最新已发布版本行；无已发布版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapToolDef> findLatestPublished(@NonNull String toolId);

    /**
     * 按 MCP 绑定关系查找工具定义（{@code mcp_server_id + mcp_tool_name}）。
     *
     * <p>⚠️ <b>为什么需要这个反查</b>：MCP 工具<b>不出现在</b> Skill 的
     * {@code declaredTools} 里 —— Skill 通过 {@code declaredMcp[{serverId, toolAllowlist}]}
     * 声明 MCP 操作（§4.2）。故「操作（op）→ 工具定义」无法沿声明边得到，
     * 只能按 MCP 绑定关系反查。
     *
     * <p>该反查是 ForcedSafety 的前提：逐操作的风险判定需要该操作的
     * {@code riskLevel}（§4.4）。
     *
     * @param mcpServerId MCP Server 标识
     * @param mcpToolName MCP 远端操作名
     * @return 与该 MCP 操作绑定的最新已发布工具定义；未绑定时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapToolDef> findByMcpBinding(@NonNull String mcpServerId,
                                                    @NonNull String mcpToolName);
}
