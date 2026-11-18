package cn.studykid.multiagent.ports.agentchat.audit;

import java.time.LocalDate;

import org.jspecify.annotations.NonNull;

/**
 * 审计目录的保留期清理端口（PIPL §7「文件侧加清理任务」）。
 *
 * <p><b>⚠️ 为什么要有这层端口</b>：目录清理是文件系统操作（实现放在
 * {@code agent-infrastructure}），而保留期策略（{@code retention.days} 来自前端配置）
 * 的编排在 {@code agent-application}。application 不得反向依赖 infrastructure
 * （ADR-011），因此两侧只能通过本端口相遇。
 *
 * <p><b>⚠️ 判据是分区日期而不是文件 mtime</b>：分区目录名由记录自身的
 * {@code startedAt}（UTC）决定，是写入时冻结的语义时间；mtime 会被备份 / 拷贝 /
 * 解压改写。按 mtime 判保留期，一次 {@code rsync} 就能把所有历史文件
 * 变成「刚写入的」——于是永远不过期，而系统没有任何异常信号。
 */
public interface ChatAuditDirectoryRetentionPort {

    /**
     * 删除目录下所有早于 {@code cutoffDate} 的日期分区。
     *
     * <p>只删本插件自己写出的文件（{@code audit-*.json} / {@code rejected-*.json}），
     * 分区内出现未识别条目时整个目录保留不动（宁可少删，不代他人决定）。
     *
     * <p>⚠️ 目录不存在不是错误（可能还没写过任何审计），返回 0。
     *
     * @param directory  审计根目录（配置项 {@code sinks.file.directory} 的原值）
     * @param cutoffDate 早于该日期（UTC）的分区整体过期
     * @return 实际删除的文件数
     * @throws IllegalStateException 目录不可读——调用方必须把它计为一次失败，
     *                               否则「清理没跑」会被误读成「没有到期文件」
     */
    int purgeOlderThan(@NonNull String directory, @NonNull LocalDate cutoffDate);
}
