package cn.studykid.multiagent.infrastructure.agentchataudit;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditDirectoryRetentionPort;

/**
 * 对话审计目录的保留期清理（PIPL §7「文件侧加清理任务」）。
 *
 * <p><b>⚠️ 为什么必须有它</b>：DB 里有保留期删除，而磁盘上是<b>同一份原文的另一份拷贝</b>。
 * 只删库不删盘 = 原文按承诺「已删除」的同时仍完整躺在
 * {@code /data/agent/chat-audit/2026-08-01/} 里。这类「双写只清理一半」
 * 是合规举证最不能出现的情形。
 *
 * <p><b>⚠️ 安全边界（本类只删自己写的东西）</b>：
 * <ol>
 *   <li>只进入名字<b>严格匹配</b> {@code yyyy-MM-dd} 的分区目录（
 *       {@code hour} 粒度时再多进一层 {@code HH}）；</li>
 *   <li>只删文件名严格匹配 {@code audit-*.json} / {@code rejected-*.json}
 *       （含写入中断残留的 {@code .tmp-*}）；</li>
 *   <li>分区内出现任何<b>不认识的条目</b>（人工放进去的评估标注、脚本产物…）
 *       → 整个目录<b>不删</b>，只打一次 WARN。宁可少删一个目录，
 *       也不替别人决定「这个文件也该消失」；</li>
 *   <li>符号链接一律不跟随、不进入（{@code NOFOLLOW_LINKS}）——
 *       否则一个指向 {@code /} 的软链就能让清理任务变成删库脚本。</li>
 * </ol>
 *
 * <p><b>⚠️ 判据是「分区日期 &lt; cutoff 日期」而不是「文件 mtime」</b>：
 * 分区日期由记录自身的 {@code startedAt}（UTC）决定，是<b>写入时冻结的语义时间</b>；
 * mtime 会被备份/拷贝/解压改写。用 mtime 判保留期，会在一次
 * {@code rsync} 之后把所有历史文件变成「刚写入的」，从而永不过期。
 */
public class DirectoryChatAuditRetention implements ChatAuditDirectoryRetentionPort {

    private static final Logger log = LoggerFactory.getLogger(DirectoryChatAuditRetention.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 日期分区目录名（严格）。 */
    private static final Pattern DAY_DIR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** 小时子目录名（严格）。 */
    private static final Pattern HOUR_DIR = Pattern.compile("\\d{2}");

    /** 本 sink 写出的文件名：audit-* / rejected-*，可带 {@code .tmp-<nano>} 后缀。 */
    private static final Pattern OWN_FILE =
            Pattern.compile("(?:audit|rejected)-[A-Za-z0-9._-]+\\.json(?:\\.tmp-\\d+)?");

    /**
     * 端口实现：接收配置里的目录字符串，把 IO 失败翻译成本方法契约约定的异常。
     *
     * <p>⚠️ 这里把 {@link IOException} 包成 {@link IllegalStateException} 而不是返回 0：
     * 「目录读不了」与「没有到期文件」在返回值上完全一样，若都返回 0，
     * 清理失败就会伪装成清理完成——而这恰恰是本项目反复治理的形态。
     */
    @Override
    public int purgeOlderThan(@NonNull String directory, @NonNull LocalDate cutoffDate) {
        Path root;
        try {
            root = Path.of(directory);
        } catch (RuntimeException e) {
            throw new IllegalStateException("审计目录配置不是合法路径: " + directory, e);
        }
        try {
            return purgeOlderThan(root, cutoffDate).filesDeleted();
        } catch (IOException e) {
            throw new IllegalStateException("审计目录清理失败: dir=" + directory, e);
        }
    }

    /**
     * 清理一个根目录下所有早于 cutoff 日期的分区。
     *
     * @param root       审计根目录（来自 {@code sinks.file.directory}）
     * @param cutoffDate 早于该日期的分区整体过期（UTC）
     * @return 清理统计
     * @throws IOException 根目录不可读——由调用方决定是否整轮失败（读取失败必须可见）
     */
    public Report purgeOlderThan(@NonNull Path root, @NonNull LocalDate cutoffDate)
            throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            // 目录不存在 = 还没写过任何审计（或已改成 DB-only）：不是错误。
            return Report.EMPTY;
        }
        int files = 0;
        int dirs = 0;
        int kept = 0;
        try (DirectoryStream<Path> partitions = Files.newDirectoryStream(root)) {
            for (Path partition : partitions) {
                String name = partition.getFileName().toString();
                if (!DAY_DIR.matcher(name).matches()) {
                    continue;   // 不是我们建的分区（运维放的 README、备份目录…）：不碰
                }
                if (!Files.isDirectory(partition, LinkOption.NOFOLLOW_LINKS)) {
                    continue;   // 同名文件或符号链接：不碰
                }
                LocalDate day;
                try {
                    day = LocalDate.parse(name, DAY);
                } catch (DateTimeParseException e) {
                    log.warn("审计分区目录名匹配 yyyy-MM-dd 但不是合法日期，跳过: dir={}", partition);
                    continue;
                }
                if (!day.isBefore(cutoffDate)) {
                    continue;   // 未到期
                }
                PurgeOutcome outcome = purgePartition(partition);
                files += outcome.filesDeleted();
                dirs += outcome.dirsDeleted();
                kept += outcome.unknownKept();
            }
        }
        if (files > 0 || dirs > 0) {
            log.info("对话审计目录已按保留期清理: root={}, cutoff={}, files={}, dirs={}",
                    root, cutoffDate, files, dirs);
        }
        if (kept > 0) {
            // ⚠️ 必须可见：保留未识别条目是**有意的少删**，不是失败。
            //    若这条 WARN 长期出现，说明有人往审计分区里写别的东西，磁盘不会被清干净。
            log.warn("审计分区存在未识别条目，已保留（不代为删除）: root={}, entries={}",
                    root, kept);
        }
        return new Report(files, dirs, kept);
    }

    /** 清理单个到期分区（及其小时子目录）。 */
    private PurgeOutcome purgePartition(Path partition) throws IOException {
        int files = 0;
        int unknown = 0;
        List<Path> subdirs = new ArrayList<>();
        try (Stream<Path> entries = Files.list(partition)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        && HOUR_DIR.matcher(entry.getFileName().toString()).matches()) {
                    subdirs.add(entry);
                } else if (isOwnFile(entry)) {
                    if (Files.deleteIfExists(entry)) {
                        files++;
                    }
                } else {
                    unknown++;
                }
            }
        }
        for (Path subdir : subdirs) {
            files += purgeHourDir(subdir);
        }
        int dirs = 0;
        for (Path subdir : subdirs) {
            if (deleteIfEmpty(subdir)) {
                dirs++;
            }
        }
        if (unknown == 0 && deleteIfEmpty(partition)) {
            dirs++;
        }
        return new PurgeOutcome(files, dirs, unknown);
    }

    /** 清理小时子目录内的自有文件。 */
    private int purgeHourDir(Path hourDir) throws IOException {
        int files = 0;
        try (Stream<Path> entries = Files.list(hourDir)) {
            for (Path entry : entries.toList()) {
                if (isOwnFile(entry) && Files.deleteIfExists(entry)) {
                    files++;
                }
            }
        }
        return files;
    }

    /** 是否为本 sink 写出的普通文件（符号链接一律不算）。 */
    private static boolean isOwnFile(@Nullable Path entry) {
        if (entry == null) {
            return false;
        }
        if (Files.isSymbolicLink(entry)) {
            return false;
        }
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        return OWN_FILE.matcher(entry.getFileName().toString()).matches();
    }

    /**
     * 仅在<b>空目录</b>时删除。
     *
     * <p>⚠️ 用 {@code deleteIfExists} + 「先确认空」两步而不是
     * {@code delete} 递归：这个类没有「递归删除」这个能力，
     * 因此也就不可能因参数错误删掉一棵不该删的树。
     */
    private static boolean deleteIfEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) {
                return false;
            }
        }
        Files.deleteIfExists(dir);
        return true;
    }

    /** 清理统计。 */
    public record Report(int filesDeleted, int dirsDeleted, int unknownKept) {
        public static final Report EMPTY = new Report(0, 0, 0);
    }

    private record PurgeOutcome(int filesDeleted, int dirsDeleted, int unknownKept) {
    }
}
