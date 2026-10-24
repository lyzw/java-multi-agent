package cn.studykid.multiagent.ports.persistence;

import java.util.List;
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
     * 插入一条版本行（§6.3 第 1 条 · W-PERM-P5）。
     *
     * <p>⚠️ <b>本方法在发布事务内被调用</b>：版本行插入、release 记录、
     * 指针 CAS 必须同事务提交（§6.3 第 1 条）。
     * 实现<b>不得</b>自行提交，否则外层事务失败时无法回滚本次插入，
     * 会留下「版本行已存在、指针未移动」的半成品当前发布（PUB-1）。
     *
     * <p>⚠️ 凭据仅存 {@code credentialRef}（定位符），
     * 凭据材料永不入库（06 §5 / §6.4）。
     *
     * <p>幂等：同一 {@code (id, version)} 已存在时返回 {@code false}，
     * <b>不覆盖</b>既有行（不可变版本行，06 §1.1）。
     *
     * @param def 版本行
     * @return {@code true} 表示本次插入；{@code false} 表示已存在
     */
    boolean insert(@NonNull CapMcpServerDef def);

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

    /**
     * 列出全部 MCP Server 定义（各逻辑 id 的<b>最高</b>版本行）。
     *
     * <p>⚠️ <b>只读用途</b>：供配置台的 MCP 目录页展示「平台上有哪些 MCP Server、
     * 各自的传输方式 / 信任层级 / 连接风险」。
     * <b>不</b>用于授权判定中的版本解析（那必须走 {@link cn.studykid.multiagent.ports.persistence.PublishedVersionPort}）。
     *
     * <p>⚠️ <b>为什么不按租户过滤</b>：MCP Server 定义是 platform 级对象
     * （{@code cap_mcp_server_def} 不启用 RLS），全平台唯一。
     *
     * <p>⚠️ <b>本端口只读，不提供写入入口</b>：平台级对象的变更
     * 走带外运维通道（见 {@code CapConfigPublishController} 的 403 作用域闸门），
     * 不挂在租户身份上 —— 否则任一租户管理员自授角色即可改全平台配置。
     *
     * @return 全部 MCP Server 定义（每个逻辑 id 取其最高版本行），按 id 升序
     */
    @NonNull List<CapMcpServerDef> listAll();
}
