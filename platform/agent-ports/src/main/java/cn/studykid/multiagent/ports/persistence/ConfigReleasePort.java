package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ConfigRelease;

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
}
