package cn.studykid.multiagent.testsupport.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IT 目标库隔离判据：<b>集成测试的连接串不得指向应用库</b>。
 *
 * <p><b>它防的是什么</b>：{@code *IT} 曾与应用实例共用 {@code agent_dev}。共用期间出现过一类
 * 「失败现场指向错误方向」的故障 —— IT 造的 {@code QUEUED} Run 被常驻实例领走并改写终态，
 * 或者反过来，历史残留的僵尸 {@code RUNNING} 占满 {@code claimTasks} 的 {@code LIMIT} 配额，
 * 让后来者的领取断言报「任务未被领取」，看起来像 claim 逻辑坏了。
 * 更糟的是 {@code execution_snapshot} 受 {@code forbid_snapshot_mutation} 保护、<b>物理删不掉</b>，
 * 每跑一轮就永久多一份，污染不可逆。
 *
 * <p><b>为什么判据要放在代码里，而不是只放脚本里</b>：{@code scripts/it-env.sh} 只是
 * <b>一条</b>入口。IDE 里单跑一个 IT、CI 直接 {@code mvn verify}、或者谁手写
 * {@code export IT_PG_URL=...agent_dev}，都绕得开它。判据放在测试里，才跟着构建走。
 *
 * <p><b>为什么不做数据库连通性/结构检查</b>：surefire 阶段连库会让「单元测试」这个阶段
 * 依赖外部服务，任何一次网络抖动都会变成假红；而「库是空的/结构是旧的」在 IT 里有
 * 更准确的报错。结构自检由 {@code scripts/it-env.sh --probe} 负责（表数/RLS/策略）。
 * 本类只做一件<b>确定的、零外部依赖</b>的字符串判据。
 *
 * <p><b>诚实边界</b>：比对的是<b>库名</b>。若把应用库改了名、又恰好把 {@code IT_PG_URL}
 * 指到同一个库，名字不同就查不出来 —— 因此判据同时读 {@code platform.env} 里声明的
 * 应用连接串做交叉比对（{@link #applicationDatasourceUrl()}），只要两边库名相同就判红，
 * 不依赖硬编码的名字。
 */
public final class ItTargetDatabase {

    /** IT 连接串环境变量名。ITs 用 {@code @EnabledIfEnvironmentVariable} 靠它守门。 */
    public static final String IT_URL_ENV = "IT_PG_URL";

    /**
     * 已知的<b>应用库</b>名（硬编码兜底）。
     *
     * <p>为什么还需要硬编码：{@code platform.env} 在 CI 里可能不存在（连接串由环境变量注入），
     * 此时交叉比对无从下手，只剩这份名单能挡。
     */
    public static final List<String> APPLICATION_DATABASES = List.of("agent_dev", "study_rag");

    /** {@code jdbc:postgresql://host:port/db?p=v} 与短形式 {@code jdbc:postgresql:db}。 */
    private static final Pattern PG_URL =
            Pattern.compile("^jdbc:postgresql:(?://[^/]+/)?([^?;/]+)");

    private static final String ENV_FILE_NAME = "platform.env";

    private static final String APP_URL_KEY = "SPRING_DATASOURCE_URL";

    private ItTargetDatabase() {}

    /**
     * 从 JDBC 连接串取出库名。
     *
     * @return 库名；不是 PostgreSQL 连接串或无法解析时返回 {@code null}
     */
    public static String databaseName(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String url = jdbcUrl.strip();
        Matcher m = PG_URL.matcher(url);
        if (!m.find()) {
            return null;
        }
        String db = m.group(1).strip();
        return db.isEmpty() ? null : db;
    }

    /**
     * 检查 IT 目标库是否构成污染风险。
     *
     * @param itUrl  IT 连接串（{@code IT_PG_URL}）；空白表示 IT 会整批跳过，无风险
     * @param appUrl 应用连接串（来自 {@code platform.env}）；可为空白，此时只走硬编码名单
     * @return 违规说明（可直接给人看）；无风险时为空
     */
    public static Optional<String> violation(String itUrl, String appUrl) {
        if (itUrl == null || itUrl.isBlank()) {
            return Optional.empty();
        }
        String itDb = databaseName(itUrl);
        if (itDb == null) {
            return Optional.of("无法从 " + IT_URL_ENV + " 解析出库名：" + masked(itUrl)
                    + "\n  期望形如 jdbc:postgresql://<host>:<port>/<隔离库>");
        }

        boolean knownAppDb = APPLICATION_DATABASES.stream().anyMatch(d -> d.equalsIgnoreCase(itDb));
        String appDb = databaseName(appUrl);
        boolean sameAsDeclaredApp = appDb != null && appDb.equalsIgnoreCase(itDb);

        if (!knownAppDb && !sameAsDeclaredApp) {
            return Optional.empty();
        }

        String why = knownAppDb
                ? "它是应用库（" + String.join(" / ", APPLICATION_DATABASES) + "）"
                : "它正是 platform.env 里声明的应用库（" + APP_URL_KEY + " 的库名 " + appDb + "）";
        return Optional.of(IT_URL_ENV + " 指向了 " + itDb + "（" + masked(itUrl) + "），"
                + why + "。\n"
                + "  共用应用库时，常驻实例会抢走 IT 造的 QUEUED Run、改写终态，"
                + "断言变成竞态；execution_snapshot 受不可变触发器保护、物理删不掉，污染不可逆。\n"
                + "  改用隔离库（推荐）：source scripts/it-env.sh && export AI_AGENT_PASSWORD='...'");
    }

    /**
     * 同 {@link #violation}，违规时直接抛异常 —— 供需要「在测试里当场炸掉」的调用点使用。
     *
     * @throws IllegalStateException 目标库是应用库
     */
    public static void requireIsolated(String itUrl, String appUrl) {
        violation(itUrl, appUrl).ifPresent(msg -> {
            throw new IllegalStateException("IT 目标库隔离被判据拒绝：" + msg);
        });
    }

    /** 环境变量形式的便捷重载（{@code IT_PG_URL} vs {@code platform.env} 声明的应用串）。 */
    public static void requireIsolatedFromEnvironment() {
        requireIsolated(System.getenv(IT_URL_ENV), applicationDatasourceUrl().orElse(""));
    }

    /**
     * 定位仓库根下的 {@code platform.env}：从 {@code user.dir} 逐级上溯查找。
     *
     * <p>surefire 的 {@code user.dir} 是模块目录（如 {@code platform/agent-infrastructure}），
     * 所以必须上溯，不能写死相对路径。
     */
    public static Optional<Path> applicationEnvFile() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(ENV_FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** {@code platform.env} 里声明的应用连接串；文件或键缺失时为空。 */
    public static Optional<String> applicationDatasourceUrl() {
        return applicationEnvFile().flatMap(f -> value(f, APP_URL_KEY));
    }

    /**
     * 读 {@code KEY=VALUE} 形式的环境文件（跳过注释与空行，去掉包裹的引号）。
     *
     * @return 键存在且值非空时返回值
     */
    public static Optional<String> value(Path envFile, String key) {
        List<String> lines;
        try {
            lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (!line.substring(0, eq).strip().equals(key)) {
                continue;
            }
            String v = line.substring(eq + 1).strip();
            if (v.length() >= 2
                    && ((v.startsWith("\"") && v.endsWith("\""))
                            || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1);
            }
            return v.isBlank() ? Optional.empty() : Optional.of(v);
        }
        return Optional.empty();
    }

    /** 日志里不出现完整口令：连接串常带 {@code ?password=...}。 */
    private static String masked(String jdbcUrl) {
        return jdbcUrl.replaceAll("(?i)(password=)[^&]*", "$1***");
    }
}
