package cn.studykid.multiagent.bootstrap.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cn.studykid.multiagent.context.CompactionPipeline.Summarizer;
import cn.studykid.multiagent.context.ContextAssembler;
import cn.studykid.multiagent.context.GlobalContextPolicy;
import cn.studykid.multiagent.ports.agentchat.ConsoleBudgetPort;
import cn.studykid.multiagent.ports.modelcatalog.ModelCatalogPort;

/**
 * S3-M9 L3 ConsoleChat 预算保护的<b>独立组合根装配片</b>（ADR-013 D52 / 开发计划 §4.1）。
 *
 * <p>⚠️ <b>为什么单独建一个 {@code @Configuration}</b>：S1 的
 * {@link AgentContextConfiguration} 只负责「配置投影 + 计量工具」，M7 的
 * {@link ContextRequestAssemblyConfiguration} 只负责 Run 专用装配入口。
 * ConsoleChat 是 D49④ 定义的<b>独立协议</b>——无 Run / Agent 定义 / 租约 / checkpoint，
 * 接线边界必须独立成片，升级守卫时能精确指向本类，不把 Run 入口的身份语义混进来。
 *
 * <p>⚠️ <b>复用唯一算法内核</b>：{@code ContextAssembler} / {@code Summarizer} / 水位阈值
 * 都直接来自 {@link ContextRequestAssemblyConfiguration} 的既有 Bean；本类⛔ 不
 * {@code new ContextAssembler(...)}、不另造 {@code TokenCounter}、不改 yml 阈值口径。
 *
 * <p>⚠️ <b>零观测 / 零会话态 / 零中间件</b>：本片不注入
 * {@code ModelCallObserver} / {@code ObservableModelGateway} / 任何 MQ / Redis / 外部服务；
 * adapter 是单例但只持有不可变依赖，不跨请求持有状态、不读写 checkpoint。
 *
 * <p><b>多构造纪律</b>：全部用 {@code @Bean} 显式 {@code new}，不依赖组件扫描猜构造器
 * （项目铁律 #3）；{@code @ConditionalOnMissingBean} 只用于允许测试切片覆盖端口，
 * 不加 {@code @Primary}。
 */
@Configuration
public class ConsoleBudgetConfiguration {

    /**
     * 唯一的 L3 Console 预算保护端口。
     *
     * <p>⚠️ {@link ModelCatalogPort} 标为可选注入：裁剪过的 Spring 测试切片可以不带持久层 Bean；
     * 但一旦总开关打开而模型目录缺失，{@link ConsoleBudgetAdapter} 会按 R10 把能力当作
     * unknown（上调 reserve），⛔ 绝不拿别的模型窗口算预算。
     * 生产装配非 null 由 {@code ConsoleBudgetWiringTest} 断言。
     */
    @Bean
    @ConditionalOnMissingBean(ConsoleBudgetPort.class)
    public @NonNull ConsoleBudgetPort consoleBudgetPort(
            @NonNull GlobalContextPolicy globalContextPolicy,
            @NonNull ContextAssembler contextAssembler,
            @NonNull Summarizer contextNoOpSummarizer,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @Nullable ModelCatalogPort modelCatalogPort) {
        return new ConsoleBudgetAdapter(
                globalContextPolicy, contextAssembler, contextNoOpSummarizer, modelCatalogPort);
    }
}
