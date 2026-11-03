package cn.studykid.multiagent.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布头推导的<b>构建期同源门禁</b>（2026-10-29 · CONFIG_UNAVAILABLE 根治的配套）。
 *
 * <p><b>要防的事</b>：「指针 → 发布头 → {@code object_versions}」是运行期解析精确版本的
 * <b>唯一</b>口径，没有任何一条 SQL 读 {@code lifecycle_state}。
 * 种子只灌定义表会复现 2026-10-29 现场的库：PLATFORM 头只有 2 个对象、TENANT 指针 0 条，
 * 而目录有 14 个已发布对象 → 除 {@code e2e-minimax} 外每个 Agent 闭包算不出 →
 * Run 无能力快照 → {@code CONFIG_UNAVAILABLE}。
 *
 * <p><b>⚠️ 2026-11-01 数据库脚本收敛后的形状</b>：原「补丁 + 种子附录」两份同源
 * 收敛为<b>单一权威源</b> —— {@code deploy/sql/platform/02_platform_seed.sql} 附录 A
 * （原 {@code patch_cap_release_head.sql} 已随全部增量 patch 脚本删除，见 deploy/sql/README.md
 * 收编清单）。本门禁相应改为只钉种子附录：
 * 推导正文必须存在、必须来自定义表推导（不是写死对象清单）、必须含产品 codec
 * 指纹形态、必须无事务控制（种子内联复放的前提）、必须补建缺失的 PLATFORM 指针。
 * 任何一条漂移都意味着新库会复放出「定义表有对象、发布头/指针却空缺」的坏库。
 *
 * <p>⚠️ 附录是<b>手写</b>的、不是 {@code pg_dump} 产物：重新导出种子会丢掉附录，
 * {@link #seedRetainsDerivedReleaseHeadAppendix()} 同时是那条 README 警告的构建期兜底。
 */
class SeedReleaseHeadParityTest {

    private static final String SEED_RELATIVE_PATH =
            "deploy/sql/platform/02_platform_seed.sql";

    /** 推导正文的起点标记：附录 A 的第 0 步（序列校正）。 */
    private static final String BODY_START = "-- ── 0.";

    /** 附录里产品 codec 的指纹前缀 —— 摘要口径变了必须让它红。 */
    private static final String FINGERPRINT_PREFIX = "'cfg_agg_v1:'";

    @Test
    @DisplayName("种子必须自带推导附录，且推导来自定义表而非写死对象清单")
    void seedRetainsDerivedReleaseHeadAppendix() {
        String seed = read(SEED_RELATIVE_PATH);

        assertThat(seed).contains("【附录 · 非 pg_dump 内容】").contains(BODY_START);
        // 口径：每 id 取 max(version) WHERE lifecycle_state='PUBLISHED'，三张平台定义表齐
        assertThat(seed).contains("FROM public.cap_mcp_server_def")
                .contains("FROM public.cap_skill_def")
                .contains("FROM public.cap_tool_def")
                .contains("FROM public.cap_agent_def")
                .contains("WHERE lifecycle_state = 'PUBLISHED'")
                .contains("max(version)");
        // 指纹必须按产品 codec 的字节形态拼（紧凑 + 键序 type,id,version）：
        // 用 jsonb/json 直接算会得到不同摘要，那是一份「看起来对、摘要错」的发布头。
        assertThat(seed).contains(FINGERPRINT_PREFIX)
                .contains("\"type\":\"")
                .contains("\"id\":\"")
                .contains("\"version\":");
    }

    @Test
    @DisplayName("PLATFORM 指针的「缺失则创建」不能少 —— deploy/sql 里没人写过这张表")
    void platformPointerIsCreatedWhenMissing() {
        String seed = read(SEED_RELATIVE_PATH);

        assertThat(seed).contains("INSERT INTO public.cap_config_pointer");
        assertThat(seed).contains("SELECT 'PLATFORM', 'PLATFORM', NULL");
        assertThat(seed).contains("WHERE NOT EXISTS (SELECT 1 FROM public.cap_config_pointer p");
    }

    @Test
    @DisplayName("推导不含事务控制，才能被种子内联复放")
    void derivationIsTransactionFree() {
        String seed = body(read(SEED_RELATIVE_PATH), SEED_RELATIVE_PATH);

        assertThat(seed.toUpperCase(java.util.Locale.ROOT))
                .as("种子附录推导正文里出现 BEGIN/COMMIT 会让 02_platform_seed.sql 内联复放时提前提交")
                .doesNotContain("COMMIT;")
                .doesNotContain("BEGIN;");
    }

    /** @return 从 {@link #BODY_START} 起到文件末尾的推导正文（去掉尾部空白） */
    private static String body(String text, String path) {
        int at = text.indexOf(BODY_START);
        assertThat(at).as(path + " 里找不到推导正文的起点 " + BODY_START).isGreaterThan(0);
        return text.substring(at).stripTrailing();
    }

    private static String read(String relativePath) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("读取失败: " + candidate, e);
                }
            }
        }
        throw new IllegalStateException("找不到 " + relativePath
                + "（从 " + Path.of("").toAbsolutePath() + " 向上找 8 层）。"
                + " 该文件是本门禁的断言对象，被改名或删除都应显式失败，不可静默跳过。");
    }
}
