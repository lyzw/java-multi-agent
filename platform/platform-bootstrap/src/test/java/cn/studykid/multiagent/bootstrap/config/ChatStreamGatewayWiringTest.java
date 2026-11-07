package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.ports.model.ChatStreamGateway;

/**
 * {@link WorkerExecutionConfig#chatStreamGateway()} 的 Bean 守卫。
 *
 * <p>⚠️ <b>为何独立成测试</b>：{@code ConsoleChatController} 构造器需要
 * {@code ChatStreamGateway}，但该类此前<b>从未注册对应 Bean</b>——
 * 应用上下文因此无法启动。单元测试用「直接构造 + 替身」绕过了这一步，
 * 导致该致命缺陷长期未被发现（production-only 的断链）。
 *
 * <p>本测试在<b>无 Spring 上下文、无需环境变量</b>的前提下，直接调用配置类方法，
 * 断言网关实例确实存在且是 {@code ChatStreamGateway}。一旦有人误删该 Bean 方法，
 * 此处会因<b>编译失败</b>（方法不存在）或断言失败而立刻暴露。
 *
 * <p>完整「上下文能否启动 + 端点是否联通」由
 * {@code WorkerLoopWiringTest}（{@code IT_PG_URL} 门禁）在集成环境覆盖。
 */
class ChatStreamGatewayWiringTest {

    @Test
    @DisplayName("chatStreamGateway() 返回非空的 ChatStreamGateway Bean")
    void chatStreamGatewayBeanIsPresent() {
        WorkerExecutionConfig config = new WorkerExecutionConfig();
        // 未配置 api-key 时回落 FakeModelGateway（同样实现 ChatStreamGateway）
        ChatStreamGateway gateway = config.chatStreamGateway();

        assertThat(gateway)
                .isNotNull()
                .isInstanceOf(ChatStreamGateway.class);
    }
}
