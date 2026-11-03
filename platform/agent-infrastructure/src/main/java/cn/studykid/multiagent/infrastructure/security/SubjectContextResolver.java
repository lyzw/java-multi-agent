package cn.studykid.multiagent.infrastructure.security;

import cn.studykid.multiagent.core.tenant.SubjectContext;

import org.jspecify.annotations.NonNull;

/**
 * 当前请求主体上下文解析器 —— 认证入口唯一写入 {@code scope} 的注入点（12 文 §2.2）。
 *
 * <p>由运行时（Spring 安全上下文 / 运维通道路由）注入实现；P0 骨架定义契约，
 * P1 阶段接入实际解析。解析失败或返回 {@code null} 时，{@code AccessGate} 转为
 * {@link AccessDeniedException}（fail-closed）。
 */
@FunctionalInterface
public interface SubjectContextResolver {

    @NonNull SubjectContext resolve();
}
