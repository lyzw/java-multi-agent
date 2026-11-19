package cn.studykid.multiagent.bootstrap.config;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.servlet.DispatcherType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import cn.studykid.multiagent.api.agentchataudit.ChatAuditWebFilter;
import cn.studykid.multiagent.infrastructure.agentchataudit.AsyncChatAuditWriter;
import cn.studykid.multiagent.infrastructure.agentchataudit.ChatAuditRecorder;
import cn.studykid.multiagent.infrastructure.agentchataudit.DirectoryChatAuditSink;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditConfigRepository;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditRecordQueryRepository;
import cn.studykid.multiagent.infrastructure.persistence.agentchataudit.JdbcChatAuditSink;
import cn.studykid.multiagent.infrastructure.persistence.cap.PlatformOpsDataSourceProvider;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfig;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditConfigPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecordQueryPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditRecorderPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditSinkPort;
import cn.studykid.multiagent.ports.agentchat.audit.ChatAuditWriterPort;
import cn.studykid.multiagent.worker.retention.ChatAuditRetentionJob;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 对话审计插件的组合根装配（P0 配置底座 + P2 落库 + P3 钩子）。
 *
 * <p><b>⚠️ 仓储 / sink 不挂 {@code @Repository}</b>：只由本类显式注册，
 * 与 {@code JdbcAgentChatAuditRepository} / {@code JdbcDictionaryRepository} 同一纪律，
 * 避免测试切片组件扫描意外拾取后出现「两个同类型 Bean」或「未绑租户的连接」。
 *
 * <p><b>⚠️ 配置无缓存</b>：{@link ChatAuditConfigPort} 每次 {@code get()} 读一次 DB，
 * 前端保存后下一个聊天请求即生效，无需重启 / 清缓存。
 *
 * <p><b>⚠️ 应急熔断</b>：{@code agent.chat-audit.emergency-stop=true} 是<b>启动期</b>
 * 最高优先级开关（运维在配置页面点不动的时候用）。它同时做两件事：
 * 过滤器整体不注册（零开销）、两个装饰器不包装（返回原实例）。
 * 普通开关仍走 DB 配置（{@code enabled=false}），下一个请求即停。
 *
 * <p><b>⚠️ writer 关闭时必须 close</b>：daemon 线程 + 有界队列，停机不排空会丢最后一批记录。
 */
@Configuration
public class ChatAuditConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditConfiguration.class);

    /** 应急熔断（启动期，优先级最高）。 */
    @Value("${agent.chat-audit.emergency-stop:false}")
    private boolean emergencyStop;

    /** 单例配置仓储（全局表 public.chat_audit_config，主数据源，无 RLS）。 */
    @Bean
    public ChatAuditConfigPort chatAuditConfigPort(DataSource dataSource) {
        log.info("装配对话审计配置端口: public.chat_audit_config，无缓存（写入即生效），应急熔断={}",
                emergencyStop);
        return new JdbcChatAuditConfigRepository(dataSource);
    }

    /**
     * 配置快照提供者：给 sink / writer 读「当前生效的开关与容量」。
     *
     * <p>⚠️ 不用缓存值：目录、采样率、队列容量都是前端可改项，
     * 缓存成字段会让「改了配置没生效」变成长期静默偏差。
     */
    private ChatAuditConfig currentConfig(ChatAuditConfigPort port) {
        try {
            return port.get().config();
        } catch (RuntimeException e) {
            log.warn("读取对话审计配置失败，本次回落安全默认值", e);
            return ChatAuditConfig.defaults();
        }
    }

    /** 审计写入器（异步有界队列 + 双写扇出；停机排空）。 */
    @Bean(destroyMethod = "close")
    public ChatAuditWriterPort chatAuditWriter(ChatAuditConfigPort configPort,
                                               DataSource dataSource) {
        List<ChatAuditSinkPort> sinks = new ArrayList<>();
        // 双写：DB 与目录各自看自己的开关（配置改动下一次写入即生效）
        sinks.add(new JdbcChatAuditSink(dataSource, () -> currentConfig(configPort)));
        sinks.add(new DirectoryChatAuditSink(() -> currentConfig(configPort)));
        log.info("装配对话审计写入器: sinks={}, 队列硬上限=100000（有效容量按配置动态裁决）",
                sinks.stream().map(ChatAuditSinkPort::name).toList());
        return new AsyncChatAuditWriter(sinks, () -> currentConfig(configPort));
    }

    /** 录制器（业务线程只做内存组装 + 非阻塞提交）。 */
    @Bean
    public ChatAuditRecorderPort chatAuditRecorder(ChatAuditWriterPort writer) {
        return new ChatAuditRecorder(writer);
    }

    /**
     * 审计记录查询端口（平台审计/效果评估角色读，跨租户）。
     *
     * <p>⚠️ <b>必须用 BYPASSRLS 运维数据源，不是主数据源</b>：{@code chat_audit_record}
     * 是 FORCE RLS 租户表，主数据源 app_agent 只能读当前绑定租户；平台审计角色无可绑租户，
     * 用主数据源会得到恒 0 行（静默空表）。运维通道未接入时
     * {@link PlatformOpsDataSourceProvider#platformIdentityDataSource()} 返回「取连接即失败」
     * 的替身 → 全局 503，绝不静默降级成主数据源。
     *
     * <p>⚠️ <b>这是运维 BYPASSRLS 数据源的一条新增（只读）消费路径</b>，已在
     * {@code PlatformOpsDataSourceArchitectureTest.PROVIDER_HOLDERS} 显式登记；
     * 仓储全类只有 SELECT，不写 outbox、不参与 Worker、不碰任何业务表。
     */
    @Bean
    public ChatAuditRecordQueryPort chatAuditRecordQueryPort(PlatformOpsDataSourceProvider platformOps) {
        log.info("装配对话审计记录查询端口: chat_audit_record + chat_audit_unattributed，"
                + "BYPASSRLS 运维数据源（enabled={}），只读跨租户", platformOps.isEnabled());
        return new JdbcChatAuditRecordQueryRepository(platformOps.platformIdentityDataSource());
    }

    /**
     * 指标出口（P4：监控 {@code sinkErrorTotal} / {@code droppedTotal}）。
     *
     * <p>⚠️ 用 {@code ObjectProvider} 取 {@code MeterRegistry} 与清理任务：
     * 两者都可能不存在（未启用 actuator / 未开启保留期清理），
     * 而「缺少指标出口」不该让整个聊天链路起不来。
     * 清理任务的失败类别必须被消费——这里改成看板可见，
     * 否则 {@code PurgeReport.failedCategories} 只是装饰性字段。
     */
    @Bean
    public ChatAuditMetrics chatAuditMetrics(
            ObjectProvider<MeterRegistry> registryProvider,
            ChatAuditWriterPort writer,
            ChatAuditConfigPort configPort,
            ObjectProvider<ChatAuditRetentionJob> retentionJob) {
        return new ChatAuditMetrics(registryProvider.getIfAvailable(), writer, configPort,
                retentionJob.getIfAvailable());
    }

    /**
     * HTTP 入口过滤器。
     *
     * <p>注册顺序 {@code HIGHEST_PRECEDENCE + 2}：紧跟 traceId（+0）与 body 缓存（+1），
     * 且早于安全链（默认 −100）——否则未认证的 401 根本没机会被记录。
     */
    @Bean
    public FilterRegistrationBean<ChatAuditWebFilter> chatAuditWebFilter(
            ChatAuditConfigPort configPort, ChatAuditRecorderPort recorder) {
        FilterRegistrationBean<ChatAuditWebFilter> reg =
                new FilterRegistrationBean<>(new ChatAuditWebFilter(
                        configPort, recorder, emergencyStop));
        reg.addUrlPatterns("/*");
        // 只处理首次 REQUEST 派发：ERROR / ASYNC 二次派发会重复建作用域（一次请求两条记录）
        reg.setDispatcherTypes(DispatcherType.REQUEST);
        reg.setName("chatAuditWebFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return reg;
    }
}
