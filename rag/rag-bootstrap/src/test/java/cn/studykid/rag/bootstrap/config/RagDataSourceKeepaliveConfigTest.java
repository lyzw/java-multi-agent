package cn.studykid.rag.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主数据源连接池保活配置守卫（2026-11-10 语文下册摄取失败根因修复）。
 *
 * <p><b>可证伪命题</b>：{@code application.yml} 的 {@code spring.datasource.hikari} 必须配置
 * {@code keepalive-time} / {@code max-lifetime} / {@code validation-timeout}，且满足
 * {@code 0 < keepalive < maxLifetime}。撤掉任意一个键，本测试变红。
 *
 * <p><b>为什么是弱文本守卫而非强绑定</b>：主数据源由 Spring Boot 自动装配，值直接来自 yml；
 * 这里用「按缩进块读键」的方式防空转——读不到块或键就失败，绝不允许「守卫扫不到 → 恒绿」。
 * 运维池（rag-platform-ops）是代码装配，其强守卫在
 * {@code RagOpsChannelTest#opsPoolMustKeepAliveToAvoidStaleConnectionOnLongIngest}。
 *
 * <p><b>根因复盘</b>：长册（146 页 / 8 段）Docling 解析十几分钟后才一次性写 embedding/chunk，
 * 期间池内连接长时间空闲，被托管 RDS 回收；Hikari 默认不保活，写阶段拿到死连接报
 * {@code This connection has been closed.}（HikariPool-1 与 rag-platform-ops 同时告警）。
 */
class RagDataSourceKeepaliveConfigTest {

    private static final String KEEPALIVE_KEY = "keepalive-time";
    private static final String IDLE_KEY = "idle-timeout";
    private static final String MAX_LIFETIME_KEY = "max-lifetime";
    private static final String VALIDATION_KEY = "validation-timeout";

    @Test
    @DisplayName("主数据源必须启用空闲保活与连接轮换，否则长册摄取会拿到死连接")
    void mainDataSourceMustKeepAlive() throws IOException {
        String yml;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml 必须在测试 classpath 上（读不到即守卫失效）")
                    .isNotNull();
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String hikariBlock = indentedBlock(yml, "    hikari:", 6);
        assertThat(hikariBlock).as("spring.datasource.hikari 配置块必须存在")
                .isNotBlank();

        long keepalive = valueOf(hikariBlock, KEEPALIVE_KEY);
        long idle = valueOf(hikariBlock, IDLE_KEY);
        long maxLifetime = valueOf(hikariBlock, MAX_LIFETIME_KEY);
        long validation = valueOf(hikariBlock, VALIDATION_KEY);

        assertThat(keepalive).as("keepalive-time 必须 > 0（默认 0=不保活，长册摄取必踩死连接）")
                .isGreaterThan(0);
        assertThat(validation).as("validation-timeout 必须 > 0")
                .isGreaterThan(0);
        assertThat(idle).as("idle-timeout 必须 < max-lifetime，否则 Hikari 会禁用空闲回收")
                .isGreaterThan(keepalive).isLessThan(maxLifetime);
        assertThat(maxLifetime).as("max-lifetime 必须 > idle-timeout > keepalive-time")
                .isGreaterThan(idle);
        assertThat(keepalive).as("keepalive 间隔须小于常见 300s 空闲回收线")
                .isLessThan(300_000L);
    }

    /** 取 {@code header}（含行首缩进）之后、缩进更深的所有行，直到缩进回落。 */
    private static String indentedBlock(String yml, String header, int childIndent) {
        String[] lines = yml.split("\\R");
        StringBuilder block = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (!inBlock) {
                if (line.equals(header)) {
                    inBlock = true;
                }
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (line.isBlank()) {
                continue;
            }
            if (indent < childIndent) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    /** 在配置块里取 {@code key: value} 的整数值；缺键或格式错直接失败（防空转）。 */
    private static long valueOf(String block, String key) {
        String[] lines = block.split("\\R");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(key + ":")) {
                String raw = t.substring((key + ":").length()).trim();
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    throw new AssertionError(key + " 不是合法整数：" + raw, e);
                }
            }
        }
        throw new AssertionError("配置块缺少 " + key + "，实际内容：\n" + block);
    }
}
