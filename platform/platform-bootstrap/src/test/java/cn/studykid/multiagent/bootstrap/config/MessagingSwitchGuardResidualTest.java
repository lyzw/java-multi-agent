package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import cn.studykid.multiagent.infrastructure.messaging.PgQueueRepository;
import org.slf4j.LoggerFactory;

/**
 * 消息通道切换守卫 · Rabbit 侧残留检查单元测试（反证）。
 *
 * <p><b>⚠️ 为什么这个测试必须存在</b>：原先的 {@code MessagingSwitchGuardIT}
 * 三条用例<b>全部传 {@code rabbitAdmin=null}</b> → Rabbit 侧残留这条路径
 * <b>从未被任何测试执行过</b>。
 * 而这条路径上恰好有一个「恒为 0」的读取缺陷：
 * {@code Properties.getProperty("QUEUE_MESSAGE_COUNT")}
 * 遇到 {@code Integer} 类型的值恒返回 {@code null}（{@code getProperty}
 * 只对 {@code String} 值返回结果）→ 深度恒为 0 →
 * 守卫永远打印「残留检查通过」→ <b>假绿</b>。
 *
 * <p><b>后果</b>：默认 {@code agent.messaging.mode=database} 时，
 * 从 rabbitmq 切到 database 正是本守卫要守的场景，
 * 而 broker 里的残留消息<b>永远检测不到</b> ——
 * 它们安静地躺在 broker 里，Run 永不执行、API 正常返回、无报错无告警。
 *
 * <p><b>本测试的判据</b>：
 * <ul>
 *   <li>U-1：队列里确有残留（{@code Integer(5)}）→ <b>必须拒绝启动</b>
 *       （此用例在修复前是<b>红的</b>：旧实现读成 0 → 放行）；</li>
 *   <li>U-2：队列确实为空（{@code Integer(0)}）→ 放行，且日志如实说「检查通过」；</li>
 *   <li>U-3：<b>读不到</b>消息数（属性缺失）→ 不得打印「检查通过」，
 *       必须如实说「不能确认队列为空」（旧实现会打印「检查通过」）。</li>
 * </ul>
 *
 * <p>⚠️ <b>本测试不跑真实 broker</b>：它要验证的是「读取与判定逻辑」，
 * 不是 broker 行为；{@code AmqpAdmin} 用桩替身即可，
 * 且必须把消息数按 <b>Spring AMQP 4 的真实类型（Integer）</b>放进去 ——
 * 否则又回到「用 String 测、线上是 Integer」的假绿陷阱。
 */
class MessagingSwitchGuardResidualTest {

    /** 队列名（与生产一致）。 */
    private static final String QUEUE_NAME = "agent.run.execute";

    /** 租户 slug（任意即可，本测试不关心具体租户）。 */
    private static final String TENANT_SLUG = "t-a";

    private ServerSocket tcpListener;
    private int tcpPort;

    private ListAppender<ILoggingEvent> guardLogs;

    @BeforeEach
    void setUp() throws IOException {
        // ⚠️ 守卫会做 TCP 预检（broker 可达才继续查残留），
        //    故需要一个真的在监听的端口。
        tcpListener = new ServerSocket(0);
        tcpPort = tcpListener.getLocalPort();

        Logger logger = (Logger) LoggerFactory.getLogger(MessagingSwitchGuard.class);
        guardLogs = new ListAppender<>();
        guardLogs.start();
        logger.addAppender(guardLogs);
    }

    @AfterEach
    void tearDown() throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(MessagingSwitchGuard.class);
        logger.detachAppender(guardLogs);
        guardLogs.stop();
        tcpListener.close();
    }

    // ── U-1：确有残留 → 必须拒绝启动（修复前此用例为红）─────────────

    @Test
    @DisplayName("⛔ 反证 U-1：Rabbit 队列确有残留时必须拒绝启动（消息数按 Spring AMQP 的 Integer 类型）")
    void refusesWhenRabbitQueueHasResidual() throws Exception {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueProperties(anyString())).thenReturn(queueProperties(5));

        MessagingChannels channels = databaseModeChannels();

        assertThatThrownBy(() -> MessagingSwitchGuard.verify(
                channels, mock(PgQueueRepository.class), admin,
                "127.0.0.1", tcpPort, QUEUE_NAME))
                .as("⚠️⚠️ 队列里有残留却放行 = 这些消息永远滞留、Run 永不执行，"
                        + "而系统表面完全正常")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("残留");
    }

    // ── U-2：确实为空 → 放行 ────────────────────────────────────────

    @Test
    @DisplayName("✅ 反证 U-2：队列确实为空时放行，且日志如实说「检查通过」")
    void passesWhenRabbitQueueIsEmpty() throws Exception {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueProperties(anyString())).thenReturn(queueProperties(0));

        MessagingChannels channels = databaseModeChannels();

        assertThatCode(() -> MessagingSwitchGuard.verify(
                channels, mock(PgQueueRepository.class), admin,
                "127.0.0.1", tcpPort, QUEUE_NAME))
                .as("守卫不得误伤正常启动")
                .doesNotThrowAnyException();

        assertThat(guardMessages())
                .as("✅ 真的查到 0 时可以如实说「检查通过」")
                .anyMatch(m -> m.contains("检查通过"));
    }

    // ── U-3：读不到 → 不得说「检查通过」─────────────────────────────

    @Test
    @DisplayName("⚠️ 反证 U-3：消息数读不到时不得声称「检查通过」，必须说「不能确认」")
    void doesNotClaimPassWhenMessageCountUnreadable() throws Exception {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        // ⚠️ 故意不放消息数字段 —— 模拟「拿不到」
        when(admin.getQueueProperties(anyString())).thenReturn(new Properties());

        MessagingChannels channels = databaseModeChannels();

        assertThatCode(() -> MessagingSwitchGuard.verify(
                channels, mock(PgQueueRepository.class), admin,
                "127.0.0.1", tcpPort, QUEUE_NAME))
                .as("读不到残留 ≠ 有残留，故不拒绝启动")
                .doesNotThrowAnyException();

        assertThat(guardMessages())
                .as("⚠️⚠️ 「查不到」绝不能被说成「检查通过」——"
                        + "那会让运维以为残留已被确认不存在")
                .noneMatch(m -> m.contains("检查通过"));
        assertThat(guardMessages())
                .as("必须如实说明「不能确认队列为空」")
                .anyMatch(m -> m.contains("不能确认"));
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /**
     * 构造 Spring AMQP 4 的真实属性形状。
     *
     * <p>⚠️ <b>必须用 Integer</b>：{@code RabbitAdmin.getQueueProperties} 写入的是
     * {@code props.put(QUEUE_MESSAGE_COUNT, queueInfo.getMessageCount())}
     * —— 值是 {@link Integer}。
     * 若测试里放成 String，就会复现不了「getProperty 返回 null」这个真实缺陷。
     */
    private static Properties queueProperties(int messageCount) {
        Properties props = new Properties();
        props.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, messageCount);
        return props;
    }

    /** 构造「database 全局模式 + 一个租户」的通道分配。 */
    private static MessagingChannels databaseModeChannels() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(UUID.randomUUID().toString());

        return MessagingChannels.resolve(dataSource, "database", "", List.of(TENANT_SLUG));
    }

    /** 取守卫打出的日志文本。 */
    private List<String> guardMessages() {
        return guardLogs.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
