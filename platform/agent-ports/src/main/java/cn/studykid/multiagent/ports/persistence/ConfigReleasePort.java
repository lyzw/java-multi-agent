package cn.studykid.multiagent.ports.persistence;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.ports.persistence.Page;

/**
 * 配置发布头仓储端口（§6.1 / 06 §1.7 · W-PERM-P0-T4）。
 *
 * <p>⚠️ <b>本端口在 P0 只提供存储能力，不含 CAS</b>：
 * 原子发布（版本行插入 + release 记录 + 当前指针 CAS <b>同事务</b>提交）
 * 属于 W-PERM-P5 的范围（06 §3.2）。P0 刻意不实现 CAS，
 * 以免在发布语义定稿前固化错误的并发协议。
 */
public interface ConfigReleasePort {

    /**
     * 保存发布记录。
     *
     * <p>⚠️ {@code configPublishSeq} 由 <b>DB 序列</b>分配（§6.3 第 2 条），
     * 禁止应用先读后加一。
     *
     * @return 已保存的发布记录（含分配的序号与标识）
     */
    @NonNull ConfigRelease save(@NonNull ConfigRelease release);

    /**
     * 按发布记录 id 读取（回滚协议用 · §6.3 第 6 条）。
     *
     * <p>⚠️ 回滚需要「按 id 精确取到目标发布」，
     * 而 {@link #findByPublishSeq} 需要调用方先知道序号 ——
     * 回滚场景下调用方手里只有 id。
     *
     * <p>⚠️ <b>必须带租户标识</b>：发布头行受 RLS 保护，
     * TENANT 作用域的行只有在该租户的上下文里才可见。
     *
     * @param tenantId  租户标识；PLATFORM 作用域传 {@code null}
     * @param releaseId 发布记录 id
     * @return 发布记录；不存在时为 {@link Optional#empty()}
     */
    @NonNull Optional<ConfigRelease> findByReleaseId(@Nullable String tenantId, long releaseId);

    /**
     * 把一条发布记录标记为 {@code ROLLED_BACK}（§6.3 第 7 条 / 06 §3.2）。
     *
     * <p>⚠️ <b>仅作审计旁路</b>：被标记的发布记录<b>不参与当前版本解析</b>，
     * 历史内容<b>一字不改</b>（06 §3.2「回滚不覆盖、不删除任何历史对象版本或历史 release 记录」）。
     *
     * <p>⚠️ <b>只有当该发布记录当前仍被指针指向时才允许标记</b> ——
     * 否则会出现「标记了一条早已不是当前的发布记录」的错账，
     * 使审计旁路指向错误的对象。
     *
     * @param tenantId  租户标识；PLATFORM 作用域传 {@code null}
     * @param releaseId 发布记录 id
     * @return {@code true} 表示已标记
     */
    boolean markRolledBack(@Nullable String tenantId, long releaseId);

    /**
     * 按发布序号读取（<b>租户作用域</b>）。
     *
     * <p>⚠️ 必须带租户标识：发布头行受 RLS 保护，
     * 读取必须发生在该租户的上下文里，否则租户行会被策略过滤掉。
     *
     * @param tenantId         租户标识
     * @param configPublishSeq 发布序号
     */
    @NonNull Optional<ConfigRelease> findByPublishSeq(@NonNull String tenantId,
                                                       long configPublishSeq);

    /**
     * 读取指定作用域下<b>最近一条已发布</b>的发布头。
     *
     * <p>⚠️ <b>只认 {@code status=PUBLISHED}</b>，再按 {@code config_publish_seq} 取最新。
     * 「当前版本不以 max(seq) 选择」的准确含义是：<b>不得</b>在全部行上取
     * max(seq)（那会选中 PREPARING 或 ROLLED_BACK 行），
     * 而必须先过滤状态再取最新（§6.3 第 2/5 条）。
     *
     * @param scope    作用域
     * @param tenantId 租户标识；{@code PLATFORM} 作用域传 {@code null}
     */
    @NonNull Optional<ConfigRelease> findLatestPublished(ConfigRelease.@NonNull Scope scope,
                                                          String tenantId);

    /**
     * 列出本租户的发布历史（发布时间线 / 回滚目标选择）。
     *
     * <p>⚠️ <b>只列 TENANT 作用域</b>：本端口服务租户级发布工作流，
     * 平台级发布头（{@code tenant_id IS NULL}）虽在 RLS 读策略下可见，
     * 但不属于本租户的时间线，且其内容由运维通道管理。
     *
     * <p>⚠️ <b>含 ROLLED_BACK 行</b>：时间线的意义正是「发生过什么」——
     * 已回滚的发布必须可见（回滚不删除历史，06 §3.2），
     * 它同时是再次回滚的目标来源。
     *
     * <p>⚠️ <b>keyset 游标按 {@code config_publish_seq} 降序</b>：
     * 序号由 DB 序列分配、全局唯一且单调，是全序键；
     * 游标二元组取 {@code (created_at, release_id)} 与既有列表实现保持一致。
     *
     * @param tenantId 租户标识（TENANT 作用域）
     * @param cursor   游标；首页传 {@code null}
     * @param limit    页大小
     * @return 分页结果（按 {@code config_publish_seq} 降序）
     */
    @NonNull Page<ConfigRelease> listReleases(@NonNull String tenantId,
                                              @Nullable String cursor,
                                              int limit);
}
