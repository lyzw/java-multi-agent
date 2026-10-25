package cn.studykid.multiagent.application.orchestration;

/**
 * 编排声明的配置期错误（多级 Agent v1.1）。
 *
 * <p>⚠️ <b>配置缺陷 ≠ 运行期拒绝</b>：本异常只在<b>配置/发布期</b>抛出，
 * 对应原因码 {@code AGENT_REF_NOT_PUBLISHED} / {@code AGENT_REF_CYCLE} 等；
 * 运行期遇到非法编排声明一律按 fail-closed 拒绝，绝不放行。
 */
public class OrchestrationConfigException extends RuntimeException {

    public OrchestrationConfigException(String message) {
        super(message);
    }

    public OrchestrationConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
