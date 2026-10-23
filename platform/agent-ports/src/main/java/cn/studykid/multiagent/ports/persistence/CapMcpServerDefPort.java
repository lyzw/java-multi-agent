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
}
