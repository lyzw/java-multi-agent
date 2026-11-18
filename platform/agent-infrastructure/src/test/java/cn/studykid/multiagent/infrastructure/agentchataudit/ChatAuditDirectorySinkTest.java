package cn.studykid.multiagent.infrastructure.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Sinks;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig.Sinks.FileSink;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditEnvelope;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRejected;

/**
 * 目录 sink 的行为验证：布局、开关、文件名净化。
 *
 * <p><b>为什么要测文件名</b>：文件名里的 {@code auditId} 源自 traceId，
 * 而 traceId 可被客户端经 {@code X-Trace-Id} 注入。未净化的 {@code ../}
 * 能把文件写到目录之外——审计文件落到别处，等于证据链自己出走。
 */
class ChatAuditDirectorySinkTest {

    private static ChatAuditConfig withFile(String directory, boolean enabled) {
        ChatAuditConfig d = ChatAuditConfig.defaults();
        Sinks sinks = new Sinks(d.sinks().database(),
                new FileSink(enabled, directory, "day", false));
        return new ChatAuditConfig(d.enabled(), d.scope(), sinks, d.capture(), d.redaction(),
                d.sampling(), d.writer(), d.retention(), d.limits());
    }

    private static ChatAuditEnvelope envelope(String auditId) {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        return new ChatAuditEnvelope(auditId, "trace-1", "/api/agent/chat/stream", "PLATFORM",
                "11111111-1111-4111-8111-111111111111", "u-1", now, now, 5L, "SUCCEEDED", 1L,
                new ChatAuditEnvelope.Http("POST", "/api/agent/chat/stream", Map.of(), 200, now),
                null, List.of(), List.of(),
                new ChatAuditEnvelope.Result("hi", "STOP", 1, 0, 1, false),
                null, false, false);
    }

    @Test
    @DisplayName("按日期分目录，一条请求一个 JSON 文件")
    void writesOneFilePerRecord(@TempDir Path dir) throws IOException {
        DirectoryChatAuditSink sink = new DirectoryChatAuditSink(
                () -> withFile(dir.toString(), true));

        sink.write(envelope("11111111-2222-4333-8444-555555555555"));

        Path file = dir.resolve("2026-11-18")
                .resolve("audit-11111111-2222-4333-8444-555555555555.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"SUCCEEDED\"", "trace-1");
    }

    @Test
    @DisplayName("⛔ 文件名净化：路径穿越字符不得逃出根目录")
    void sanitizesFileName(@TempDir Path dir) throws IOException {
        DirectoryChatAuditSink sink = new DirectoryChatAuditSink(
                () -> withFile(dir.toString(), true));

        sink.write(envelope("../../etc/passwd"));

        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> all = files.toList();
            // 所有落盘文件都必须仍在根目录之内
            assertThat(all).allSatisfy(p -> assertThat(p.startsWith(dir)).isTrue());
            assertThat(all.stream().anyMatch(p -> p.getFileName().toString().startsWith("audit-")))
                    .isTrue();
        }
        assertThat(Files.exists(Path.of(dir.getParent().toString(), "etc"))).isFalse();
    }

    @Test
    @DisplayName("关闭态不写任何文件（开关即时生效）")
    void disabledWritesNothing(@TempDir Path dir) throws IOException {
        DirectoryChatAuditSink sink = new DirectoryChatAuditSink(
                () -> withFile(dir.toString(), false));

        sink.write(envelope("11111111-2222-4333-8444-666666666666"));

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("被拒记录单独命名（rejected-*.json），不混进正式审计文件")
    void rejectedRecordUsesOwnPrefix(@TempDir Path dir) throws IOException {
        DirectoryChatAuditSink sink = new DirectoryChatAuditSink(
                () -> withFile(dir.toString(), true));

        sink.write(new ChatAuditRejected("11111111-2222-4333-8444-777777777777", "trace-2",
                Instant.parse("2026-11-18T10:00:00Z"), "/api/runs", "POST", 401,
                "未认证", Map.of("uri", "/api/runs")));

        Path file = dir.resolve("2026-11-18")
                .resolve("rejected-11111111-2222-4333-8444-777777777777.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("401").doesNotContain("Authorization");
    }

    /**
     * ⛔ PIPL §7「文件目录安全」：原文文件必须 {@code 0600}、日期分区目录 {@code 0700}。
     *
     * <p>⚠️ 这条断言的价值不在「代码写了 setPermissions」，而在<b>写成功的那个文件</b>
     * 上——权限必须在内容落盘的同一个 creat 调用里生效，否则中间存在
     * 「内容已写入、同机其他账号仍可读」的窗口。
     *
     * <p>非 POSIX 文件系统（挂载的 NTFS 等）没有权限模型，跳过而非假绿。
     */
    @Test
    @DisplayName("原文文件 0600 / 分区目录 0700（同机其他账号不可读）")
    void restrictsPermissionsToOwnerOnly(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(
                Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class),
                "当前文件系统无 POSIX 权限模型，本断言不适用");

        DirectoryChatAuditSink sink = new DirectoryChatAuditSink(
                () -> withFile(dir.toString(), true));
        Set<PosixFilePermission> rootPermsBefore = Files.getPosixFilePermissions(dir);

        sink.write(envelope("11111111-2222-4333-8444-888888888888"));

        Path partition = dir.resolve("2026-11-18");
        Path file = partition.resolve("audit-11111111-2222-4333-8444-888888888888.json");
        assertThat(file).exists();
        assertThat(Files.getPosixFilePermissions(file))
                .as("⛔ 审计原文对同机其他账号可读：PIPL 原文保护失效（须 0600）")
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(partition))
                .as("⛔ 日期分区目录他人可列举：文件名（auditId）外部可枚举（须 0700）")
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        // 配置的根目录不归本 sink 管：运维常用共享卷/挂载点，擅自改它的权限会另生故障
        assertThat(Files.getPosixFilePermissions(dir))
                .as("⛔ sink 擅自改动了配置的根目录权限")
                .containsExactlyInAnyOrderElementsOf(rootPermsBefore);
    }
}
