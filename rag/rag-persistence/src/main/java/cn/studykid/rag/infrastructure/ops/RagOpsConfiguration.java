package cn.studykid.rag.infrastructure.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 平台运维通道装配（RAG-D11 §1.3 / 第二刀）。
 *
 * <p><b>⚠️ 本类刻意不注册任何 {@code DataSource} 类型的 bean</b>：
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 是
 * {@code @ConditionalOnMissingBean(DataSource.class)}——
 * 一旦容器里出现第二个 DataSource（哪怕加了 {@code @Primary}），
 * 主数据源的自动装配就整体退避，连带
 * {@code JdbcTemplate}、{@code PlatformTransactionManager} 一起消失，
 * 表现为「加了运维池，检索链路全挂」。池子因此藏在 provider 内部，
 * 对容器只暴露 {@link RagOpsSessionPort} 这一个自有类型。
 *
 * <p><b>风险姿态（用户定案：默认开 + 常驻补偿控制）</b>：
 * {@code rag.ops.enabled} 默认 {@code true}；护栏不是「先关掉」，而是
 * ① 配置不齐直接拒绝启动（不静默变成永久 fail-closed 的哑通道）、
 * ② 窗口外取不到连接、③ 首次进窗做角色体检、④ 调用失败呈现 503 而非回落应用角色。
 *
 * <p><b>属性必须挂在 {@code rag:} 下</b>：本仓 cap 侧有过一次
 * 「ops 块被错放到别的父键下，读取回落默认值 false，通道永久 fail-closed」的事故
 * （见 {@code docs/统一配置与权限管控体系_开发计划/P6R1_平台运维角色接入说明.md}）。
 */
@Configuration
public class RagOpsConfiguration {

    /**
     * @param enabled      是否接入运维通道（默认开）
     * @param url          运维数据源 JDBC URL
     * @param username     运维角色名
     * @param password     运维角色口令（可空 = 走 trust 认证的本机库）
     * @param maxPoolSize  池上限
     * @return 会话端口；未启用时为 fail-closed 的占位实现
     */
    @Bean(destroyMethod = "close")
    public RagOpsSessionPort ragOpsSessionPort(
            @Value("${rag.ops.enabled:true}") boolean enabled,
            @Value("${rag.ops.datasource.url:}") String url,
            @Value("${rag.ops.datasource.username:}") String username,
            @Value("${rag.ops.datasource.password:}") String password,
            @Value("${rag.ops.datasource.max-pool-size:2}") int maxPoolSize) {
        if (!enabled) {
            return RagOpsDataSourceProvider.disabled("rag.ops.enabled");
        }
        String trimmedUrl = url == null ? "" : url.trim();
        String trimmedUser = username == null ? "" : username.trim();
        if (trimmedUrl.isEmpty() || trimmedUser.isEmpty()) {
            throw new IllegalStateException(
                    "rag.ops.enabled=true 但运维数据源未配置齐（缺 "
                            + (trimmedUrl.isEmpty() ? "rag.ops.datasource.url" : "rag.ops.datasource.username")
                            + "）。要么补齐 RAG_OPS_DATASOURCE_*，要么显式设 rag.ops.enabled=false；"
                            + "不允许「配置开着、通道实际不可用」这种静默状态——"
                            + "它会以 503 的面目长期存在，看起来像后端故障。");
        }
        return RagOpsDataSourceProvider.connected(
                trimmedUrl, trimmedUser, password == null ? "" : password, maxPoolSize);
    }
}
