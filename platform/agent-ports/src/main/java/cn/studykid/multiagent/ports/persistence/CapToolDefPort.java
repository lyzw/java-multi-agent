package cn.studykid.multiagent.ports.persistence;

import java.util.List;
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
     * 插入一条版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>本方法在发布事务内被调用</b>：版本行插入、release 记录、
     * 指针 CAS 必须同事务提交（§6.3 第 1 条）。
     * 实现<b>不得</b>自行提交 —— 自行提交会让外层事务的失败无法回滚本次插入，
     * 从而留下「版本行已存在、指针未移动」的半成品当前发布（PUB-1）。
     *
     * <p>幂等：同一 {@code (id, version)} 已存在时返回 {@code false}，
     * <b>不覆盖</b>既有行（不可变版本行，06 §1.1）。
     *
     * @param def 版本行
     * @return {@code true} 表示本次插入；{@code false} 表示已存在
     */
    boolean insert(@NonNull CapToolDef def);

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
     * 读取该 Tool 的<b>最高版本行</b>（<b>不区分发布状态</b> · 版本管理守卫）。
     *
     * <p>⚠️ 与 {@link #findLatestPublished} 的「已发布口径」不同：本方法取该逻辑 id 的
     * <b>绝对最高版本</b>—— 可能是已发布版本、也可能是尚未发布的草稿版本。
     * 服务端据此算「下一个版本号 = 最高版本 + 1」，避免「已有未发布草稿却撞号」的假成功。
     *
     * <p>⚠️ 管理台的「编辑」入口<b>以本方法为准</b>：拿最高版本作为编辑基线 ——
     * 若最高版本已发布，说明「当前可编辑的就是线上生效版本」；
     * 若最高版本未发布，说明「已有人改过但没发布」，后续编辑继续在该未发布版本上进行
     * （已发布版本不可覆盖，DB 层有触发器封禁 UPDATE）。
     *
     * @param toolId Tool 逻辑 id
     * @return 最高版本行；无任何版本时为 {@link Optional#empty()}
     */
    @NonNull Optional<CapToolDef> findLatest(@NonNull String toolId);

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

    /**
     * 列出全部 Tool 定义（各逻辑 id 的<b>最新</b>版本行）。
     *
     * <p>⚠️ <b>只读用途</b>：供配置台的 Tool 目录页展示「平台上有哪些工具、
     * 各自的风险等级 / 输入 schema / MCP 绑定」。
     * <b>不</b>用于授权判定中的版本解析（那必须走 {@link PublishedVersionPort}）。
     *
     * <p>⚠️ <b>为什么不按租户过滤</b>：Tool 定义是 platform 级对象
     * （{@code cap_tool_def} 不启用 RLS），全平台唯一；
     * 租户边界体现在「哪个租户的 Agent 能启用哪个 Skill」上。
     *
     * <p>⚠️ <b>本端口只读，不提供写入入口</b>：平台级对象的变更
     * 走带外运维通道（见 {@code CapConfigPublishController} 的 403 作用域闸门），
     * 不挂在租户身份上——否则任一租户管理员自授角色即可改全平台配置。
     *
     * @return 全部 Tool 定义（每个逻辑 id 取其最高版本行），按 id 升序
     */
    @NonNull List<CapToolDef> listAll();

    /**
     * 列出绑定到某个 MCP Server 的全部 Tool（<b>不限版本</b> · 第三十四批）。
     *
     * <p>⚠️ <b>为什么需要它</b>：MCP Server 的「暴露工具清单」有两种来源 ——
     * ① Server 自身的 {@code exposedToolAllowlist}（连接级允许集）；
     * ② 实际登记在该 Server 上的 Tool 定义（{@code mcp_server_id + mcp_tool_name} 绑定）。
     * 管理台要维护「Server ↔ Tool」这层关系，就必须能**反查**「这个 Server 下挂了哪些 Tool」——
     * 否则前端只能逐个 Tool 试，没有可枚举的清单。
     *
     * <p>⚠️ <b>不限版本</b>：与「出向/入向关系按 Agent 维度」的同款口径 ——
     * 关系问的是「这个 Server 与哪些 Tool 有关」，不是「某个版本版本」。
     *
     * @param mcpServerId MCP Server 标识
     * @return 该 Server 下的 Tool 定义行；无绑定时为空列表
     */
    @NonNull List<CapToolDef> listByMcpServer(@NonNull String mcpServerId);

    /**
     * 列出该 Tool 的<b>全部版本行</b>（不可变历史，按版本倒序）。
     *
     * <p>⚠️ 与 {@link #listAll()} 的「每 id 最高版本」口径不同：本方法是<b>版本维度</b>
     * 的全量回溯（能力目录版本管理 · 历史版本留存），供管理台展示版本历史与变更轨迹。
     * <b>不</b>用于授权判定中的版本解析（那必须走 {@link PublishedVersionPort}）。
     *
     * @param toolId Tool 逻辑 id
     * @return 全部版本行，按 version 倒序；无任何版本时为空列表
     */
    @NonNull List<CapToolDef> listVersions(@NonNull String toolId);
}
