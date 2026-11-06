package cn.studykid.multiagent.memory.config;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.application.prompt.PromptGuard;
import cn.studykid.multiagent.memory.application.MemoryConsolidator;
import cn.studykid.multiagent.memory.application.MemoryPolicy;
import cn.studykid.multiagent.memory.application.MemoryRecallService;
import cn.studykid.multiagent.memory.persistence.JdbcMemoryRepository;
import cn.studykid.multiagent.memory.port.MemoryPort;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;

/**
 * 跨 Run 记忆的 Spring 装配（P1）。
 *
 * <p>⚠️ <b>2026-11-06 从 {@code ApplicationConfiguration} 迁入本模块</b>：
 * 记忆的领域模型、端口、策略、持久化与装配现在同处 {@code agent-memory}，
 * 后续记忆相关改动只需在本模块内进行；组合根（platform-bootstrap）不再持有
 * 任何记忆类型的全限定名。
 *
 * <p>本类由 {@code PlatformApplication} 的
 * {@code @SpringBootApplication(scanBasePackages = "cn.studykid.multiagent")}
 * 组件扫描发现 —— 不要改成需要手工 {@code @Import} 的装配方式，
 * 否则「加了依赖却没被扫到」会表现为记忆静默关闭而<b>没有任何报错</b>。
 */
@Configuration
public class MemoryConfiguration {

    /**
     * 跨 Run 记忆仓储。
     *
     * <p>⚠️ <b>必须走主池（应用角色）</b>：{@code student_memory} 是
     * {@code FORCE ROW LEVEL SECURITY} 表，用 admin 连接会<b>绕过 RLS</b>，
     * 等于跨租户可读写 —— 且<b>不会报错</b>。
     *
     * <p>⚠️ {@code Clock} 显式注入而不是用 {@code Clock.systemUTC()}：
     * 强度衰减是<b>时间相关</b>逻辑，测试必须能拨钟。
     * 若这里硬编码系统时钟，{@code MemoryRecallIT} 就只能靠「等」来验证衰减。
     */
    @Bean
    public MemoryPort memoryPort(DataSource dataSource, Clock clock) {
        return new JdbcMemoryRepository(dataSource, clock);
    }

    /**
     * 记忆召回服务。
     *
     * <p>⚠️⚠️ <b>配置全部在此解析并 fail-closed</b>：
     * 若把 {@code @Value} 散落在 {@code RunExecutionLoop} 里，
     * 一处漏填就退化为「读的是 null」，而 {@code null} 转 int 会抛
     * {@code NullPointerException} —— 那是<b>启动期就该发现</b>的错误，
     * 不是运行期第一个 Run 才炸。
     *
     * <p>⚠️ <b>{@code enabled=false} 时返回 {@code null}</b>：
     * 这是本 Bean <b>唯一</b>的关闭方式，{@code RunExecutionLoop} 收到 null
     * 即「不召回」，并在装配时打 WARN —— 关闭是显式动作，且<b>必然可见</b>。
     * 若改成「返回一个什么都不做的实现」，关闭与「配错了」就无法区分。
     */
    @Bean
    public MemoryRecallService memoryRecallService(
            MemoryPort memoryPort,
            PromptGuard promptGuard,
            Clock clock,
            @Value("${" + MemoryPolicy.KEY_ENABLED + ":false}") String enabled,
            @Value("${" + MemoryPolicy.KEY_RECALL_MAX_ITEMS + ":5}") String recallMaxItems,
            @Value("${" + MemoryPolicy.KEY_RECALL_MAX_TOKENS + ":800}") String recallMaxTokens,
            @Value("${" + MemoryPolicy.KEY_RECALL_MIN_STRENGTH + ":0.40}") String recallMinStrength) {
        if (!MemoryPolicy.parseEnabled(enabled)) {
            return null;
        }
        return new MemoryRecallService(
                memoryPort, promptGuard,
                MemoryPolicy.requireRecallMaxItems(recallMaxItems),
                MemoryPolicy.requireRecallMaxTokens(recallMaxTokens),
                MemoryPolicy.requireRecallMinStrength(recallMinStrength),
                clock);
    }

    /**
     * 记忆整合服务。
     *
     * <p>⚠️ 与召回共用 {@code agent.memory.enabled}：
     * 两者必须同时开关。若只开召回不开整合，记忆<b>永不增长</b>
     * （表永远是空的），而日志里看不出任何异常 —— 这是最坏的一种半配置。
     */
    @Bean
    public MemoryConsolidator memoryConsolidator(
            MemoryPort memoryPort,
            ResultQueryPort resultQueryPort,
            @Value("${" + MemoryPolicy.KEY_ENABLED + ":false}") String enabled) {
        if (!MemoryPolicy.parseEnabled(enabled)) {
            return null;
        }
        return new MemoryConsolidator(memoryPort, resultQueryPort);
    }
}
