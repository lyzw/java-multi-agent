package cn.studykid.multiagent.infrastructure.agentchataudit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.function.Supplier;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Sinks.FileSink;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecord;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditSinkPort;

import tools.jackson.databind.ObjectMapper;

/**
 * 目录落盘 sink：一条请求一个 JSON 文件，按日期（或日期/小时）分目录。
 *
 * <p>布局：{@code {directory}/{yyyy-MM-dd}/audit-{auditId}.json}；
 * 建连前失败记录为 {@code rejected-{auditId}.json}（只含元数据）。
 * 单请求一文件，便于按 auditId / traceId 直接定位与人工评估标注。
 *
 * <p><b>⚠️ 路径每次从当前配置取</b>：目录、分区粒度、fsync 都是前端可改项，
 * 缓存成字段会让「改了配置没生效」变成长期静默偏差。
 *
 * <p><b>⚠️ 原子写</b>：先写临时文件再 {@code MOVE ATOMIC_MOVE}，避免评估脚本读到半截 JSON。
 *
 * <p><b>⚠️ 文件名净化</b>：auditId 来自 traceId（可含客户端传入的部分），
 * 必须只保留安全字符，否则 {@code ../} 能写到目录之外。
 */
public class DirectoryChatAuditSink implements ChatAuditSinkPort {

    private static final Logger log = LoggerFactory.getLogger(DirectoryChatAuditSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH")
            .withZone(ZoneOffset.UTC);

    /** 已告警过的目录（避免每个请求刷一条「目录不可写」）。 */
    private volatile String lastWarnedDirectory;

    /** 审计原文文件权限：仅属主可读写（PIPL：同机其他账号不得读未成年人辅导原话）。 */
    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");

    /** 日期分区目录权限：仅属主可进入/列举（文件名含 auditId，不对他人可枚举）。 */
    private static final Set<PosixFilePermission> DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");

    private final Supplier<ChatAuditConfig> configSupplier;

    public DirectoryChatAuditSink(@NonNull Supplier<ChatAuditConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public @NonNull String name() {
        return "directory";
    }

    @Override
    public void write(@NonNull ChatAuditRecord record) {
        FileSink target = fileTarget();
        if (target == null) {
            return;
        }
        String json;
        String prefix;
        Instant at;
        if (record instanceof ChatAuditEnvelope envelope) {
            json = MAPPER.writeValueAsString(envelope);
            prefix = "audit-";
            at = envelope.startedAt();
        } else if (record instanceof ChatAuditRejected rejected) {
            json = MAPPER.writeValueAsString(rejected);
            prefix = "rejected-";
            at = rejected.startedAt();
        } else {
            // sealed 接口：走到这里说明新增了记录形态却没处理（编译期已穷尽，运行期兜底）
            throw new IllegalStateException("未知的审计记录形态: " + record.getClass().getName());
        }
        writeAtomically(target, prefix + safeId(record.auditId()) + ".json", json, at);
    }

    /** 当前生效的文件出口参数；关闭态返回 {@code null}（不写、不报错）。 */
    private @Nullable FileSink fileTarget() {
        ChatAuditConfig config;
        try {
            config = configSupplier.get();
        } catch (RuntimeException e) {
            log.warn("读取审计配置失败，本批跳过目录落盘: {}", e.toString());
            return null;
        }
        FileSink file = config.sinks().file();
        return file.enabled() ? file : null;
    }

    private void writeAtomically(@NonNull FileSink target,
                                 @NonNull String fileName,
                                 @NonNull String json,
                                 @NonNull Instant at) {
        Path root = Path.of(target.directory());
        Path dir = "hour".equals(target.partition())
                ? root.resolve(DAY.format(at)).resolve(HOUR.format(at))
                : root.resolve(DAY.format(at));
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            // ⚠️ 权限在**创建时**收紧，而不是写完再 chmod：先写后改会留下一个
            //    「内容已落盘但同机其他账号仍可读」的时间窗，而那正是最该防的窗口。
            //    文件用 FileAttribute 建于打开时（见 openRestricted），目录用 setPermissions。
            tightenDirectory(dir);
            Path path = dir.resolve(fileName);
            tmp = dir.resolve(fileName + ".tmp-" + System.nanoTime());
            try (FileChannel ch = openRestricted(tmp)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    ch.write(buffer);
                }
                if (target.fsync()) {
                    ch.force(true);
                }
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            cleanupQuietly(tmp);
            warnOnce(target.directory(), e);
            throw new IllegalStateException("写审计目录失败: dir=" + dir, e);
        }
    }

    private static void cleanupQuietly(@Nullable Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 临时文件残留不影响正确性，且下一次写入用不同名字
        }
    }

    /**
     * 以 {@code 0600} 打开临时文件；非 POSIX 文件系统（如挂载的 NTFS / Windows）降级为默认权限。
     *
     * <p>⚠️ 为什么必须用 {@code FileAttribute} 而不是打开后再改：权限属性作用于
     * {@code creat(2)} 的系统调用本身，文件从存在的第一纳秒起就是 {@code 0600}；
     * 先 creat 再 chmod 会有一个「同机其他账号可读」的窗口。
     * 审计原文是未成年人辅导原话，这个窗口不可接受。
     */
    private static FileChannel openRestricted(@NonNull Path tmp) throws IOException {
        try {
            return FileChannel.open(tmp,
                    Set.of(StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(FILE_PERMS));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统：没有权限模型可收紧，退回默认打开方式。
            // ⚠️ 这不是静默失败——降级是确定的平台事实，且此时目录 ACL 由部署方负责。
            return FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }
    }

    /**
     * 把日期分区目录收紧到 {@code 0700}。
     *
     * <p>⚠️ <b>只动我们自己创建的日期分区目录</b>，不动配置的根目录：
     * 根目录可能是挂载点、共享卷或运维预建的目录，擅自改它的权限会在
     * 「审计写不进去」之外再制造一类与审计无关的故障。
     *
     * <p>⚠️ 已收紧时跳过（避免每次请求两次多余的系统调用）；
     * 非 POSIX 文件系统直接返回。
     */
    private static void tightenDirectory(@NonNull Path dir) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(
                    dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return;
            }
            if (!view.readAttributes().permissions().equals(DIR_PERMS)) {
                view.setPermissions(DIR_PERMS);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // 权限收紧失败不阻断写入（写入成功是主目标），但必须在 DEBUG 留痕：
            // 静默忽略会让「目录 755」这一事实在排查时完全查不到。
            log.debug("审计分区目录权限收紧失败（不影响写入）: dir={}, cause={}",
                    dir, e.toString());
        }
    }

    /** 同一目录只告警一次：磁盘满时每请求刷一条 WARN 会把日志变成第二个故障。 */
    private void warnOnce(@NonNull String directory, @NonNull Exception e) {
        if (directory.equals(lastWarnedDirectory)) {
            return;
        }
        lastWarnedDirectory = directory;
        log.warn("对话审计目录不可写（后续同目录错误不再重复告警）: dir={}, cause={}",
                directory, e.toString());
    }

    /** 文件名净化：只保留字母数字与 {@code . _ -}。 */
    private static @NonNull String safeId(@NonNull String auditId) {
        return auditId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
