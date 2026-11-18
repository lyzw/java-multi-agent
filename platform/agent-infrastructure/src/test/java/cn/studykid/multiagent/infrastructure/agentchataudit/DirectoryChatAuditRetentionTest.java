package cn.studykid.multiagent.infrastructure.agentchataudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 审计目录保留期清理的行为验证（PIPL §7 文件侧）。
 *
 * <p><b>本类守的是什么</b>：一个会按日期删文件的组件，写错的代价是
 * <b>不可逆的数据丢失</b>。因此三类断言缺一不可：
 * <ol>
 *   <li><b>该删的删</b>——到期分区整体消失（含 {@code hour} 子目录）；</li>
 *   <li><b>不该删的不删</b>——未到期分区、非日期目录、分区内他人放入的文件；</li>
 *   <li><b>符号链接不跟随</b>——一个指向父目录的软链就能让清理变成删库脚本。</li>
 * </ol>
 */
@DisplayName("对话审计目录保留期清理")
class DirectoryChatAuditRetentionTest {

    private static void touch(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), "{}");
    }

    @Test
    @DisplayName("到期分区整体删除（含 hour 子目录），未到期分区原样保留")
    void purgesExpiredPartitionsOnly(@TempDir Path root) throws IOException {
        touch(root.resolve("2026-10-01"), "audit-aaa.json");
        touch(root.resolve("2026-10-01"), "rejected-bbb.json");
        touch(root.resolve("2026-10-01/08"), "audit-ccc.json");
        touch(root.resolve("2026-11-17"), "audit-ddd.json");

        DirectoryChatAuditRetention retention = new DirectoryChatAuditRetention();
        DirectoryChatAuditRetention.Report report =
                retention.purgeOlderThan(root, LocalDate.parse("2026-11-11"));

        assertThat(report.filesDeleted()).isEqualTo(3);
        assertThat(root.resolve("2026-10-01"))
                .as("⛔ 到期分区目录未删：空目录会永久堆积，运维无法判断清理是否发生")
                .doesNotExist();
        assertThat(root.resolve("2026-11-17/audit-ddd.json"))
                .as("⛔ 删掉了未到期分区的文件：保留期变成「随便删」")
                .exists();
    }

    @Test
    @DisplayName("⛔ 分区内有未识别条目时整目录保留（不代他人决定该删什么）")
    void unknownEntriesAreKept(@TempDir Path root) throws IOException {
        Path partition = root.resolve("2026-10-01");
        touch(partition, "audit-aaa.json");
        touch(partition, "evaluation-notes.md");

        DirectoryChatAuditRetention.Report report =
                new DirectoryChatAuditRetention().purgeOlderThan(root, LocalDate.parse("2026-11-11"));

        assertThat(partition.resolve("evaluation-notes.md"))
                .as("⛔ 清理任务删掉了不是它写的文件（人工评估标注被当垃圾清掉）")
                .exists();
        assertThat(partition).as("⚠️ 有未识别条目时目录必须保留，否则条目失去父目录").exists();
        assertThat(report.unknownKept()).isEqualTo(1);
        // 自有文件仍应删除：保留目录不等于放弃清理
        assertThat(partition.resolve("audit-aaa.json")).doesNotExist();
    }

    @Test
    @DisplayName("非日期目录一律不碰（运维放的 README / 备份目录）")
    void nonPartitionDirectoriesUntouched(@TempDir Path root) throws IOException {
        touch(root.resolve("manual-backup"), "audit-old.json");
        touch(root, "README.md");

        DirectoryChatAuditRetention.Report report =
                new DirectoryChatAuditRetention().purgeOlderThan(root, LocalDate.parse("2026-11-11"));

        assertThat(report.filesDeleted()).isZero();
        assertThat(root.resolve("manual-backup/audit-old.json")).exists();
        assertThat(root.resolve("README.md")).exists();
    }

    @Test
    @DisplayName("⛔ 符号链接不跟随、不进入（否则一条软链就能让清理变成删库脚本）")
    void symlinksAreNotFollowed(@TempDir Path root) throws IOException {
        Path outside = Files.createDirectories(root.resolve("outside"));
        touch(outside, "audit-precious.json");
        // 造一个看起来像到期分区的软链，指向外部目录
        Path link = root.resolve("2026-10-01");
        Files.createSymbolicLink(link, outside);

        DirectoryChatAuditRetention.Report report =
                new DirectoryChatAuditRetention().purgeOlderThan(root, LocalDate.parse("2026-11-11"));

        assertThat(report.filesDeleted()).isZero();
        assertThat(outside.resolve("audit-precious.json"))
                .as("⛔ 顺着软链删到了分区目录之外")
                .exists();
    }

    @Test
    @DisplayName("根目录不存在返回 0（还没写过审计不是错误）")
    void missingRootIsNotAnError(@TempDir Path root) throws IOException {
        DirectoryChatAuditRetention.Report report =
                new DirectoryChatAuditRetention().purgeOlderThan(
                        root.resolve("nope"), LocalDate.parse("2026-11-11"));

        assertThat(report.filesDeleted()).isZero();
    }

    @Test
    @DisplayName("⛔ 路径非法必须抛异常（不能被读成「没有到期文件」）")
    void invalidPathThrows() {
        // NUL 是操作系统不接受的路径字符：Path.of 直接抛 InvalidPathException。
        // 若这里返回 0，「目录配错了」与「没有到期文件」在返回值上完全一样。
        assertThatThrownBy(() -> new DirectoryChatAuditRetention().purgeOlderThan(
                "bad\u0000path", LocalDate.parse("2026-11-11")))
                .as("⛔ 配置里的目录非法时静默返回 0：清理永久不生效且无任何信号")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法路径");
    }

    @Test
    @DisplayName("⛔ 目录不可读必须抛异常，而不是当作「没有到期文件」")
    void unreadableRootThrows(@TempDir Path root) throws IOException {
        Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")),
                "以 root 运行时权限位不生效，本断言不适用");
        Assumptions.assumeTrue(
                Files.getFileStore(root).supportsFileAttributeView(PosixFileAttributeView.class),
                "当前文件系统无 POSIX 权限模型，本断言不适用");

        Path partition = Files.createDirectories(root.resolve("2026-10-01"));
        touch(partition, "audit-aaa.json");
        PosixFileAttributeView view = Files.getFileAttributeView(
                partition, PosixFileAttributeView.class);
        assertThat(view).isNotNull();
        view.setPermissions(Set.of());
        try {
            // 权限位确实生效时才能断言（某些沙箱/挂载会忽略 chmod）
            Assumptions.assumeFalse(isListable(partition), "chmod 000 未生效，本断言不适用");

            assertThatThrownBy(() -> new DirectoryChatAuditRetention().purgeOlderThan(
                    root.toString(), LocalDate.parse("2026-11-11")))
                    .as("⛔ 目录读不了却返回 0：清理失败伪装成清理完成")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("审计目录清理失败");
        } finally {
            view.setPermissions(PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static boolean isListable(Path dir) {
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }
}
