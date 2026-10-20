package cn.studykid.multiagent.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.config.ToolRiskLevel;
import cn.studykid.multiagent.ports.tool.ToolHandler;

/**
 * ⛔ 工具风险级别**一致性守卫**（C8 接线 / P4-D11 D11-3）。
 *
 * <p><b>存在的唯一理由</b>：把「yml 里声明的风险级别」从<b>完全惰性的镜像</b>
 * 变成<b>可执行的守卫</b>。
 *
 * <p>⚠️ 接线前的形态（C8 的原始缺口）：
 * 工具的**风险级别由代码决定**（{@code ToolHandler.riskLevel()}），
 * 而 yml 的 {@code agent.tool.risk-level-map} 声明了同一事实却<b>无人读取</b>。
 * 改 yml 不改行为——<b>系统表面完全正常</b>。
 *
 * <p>⚠️ 为什么风险级别必须以<b>代码</b>为准：
 * 一个工具有没有副作用是<b>代码事实</b>。若允许 yml 把
 * {@code record_weakness} 改成 {@code READ}，按
 * {@code agent.approval.required-by-risk} 映射它就只需 {@code NOT_REQUIRED} 审批
 * → <b>有副作用的工具绕过审批</b>。这是安全退化，不可用。
 *
 * <p>⚠️ <b>fail-closed</b>：二者不一致 → <b>抛异常拒绝启动</b>并打印差异明细。
 * 绝不「以代码为准、忽略 yml」——那等于让声明继续惰性，
 * 管理员仍然会以为「改了有用」。
 *
 * <p>⚠️ <b>与 P4-D08 的 R-1 同构</b>：那一处是「工具未注册为 Bean → 工具从不执行」，
 * 这里是「声明未接线 → 声明形同虚设」。共同形态是
 * *静默失效，系统表面正常*——本项目已多次栽在它上面。
 */
public class ToolRiskLevelConsistencyGuard implements org.springframework.beans.factory.InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ToolRiskLevelConsistencyGuard.class);

    private final List<ToolHandler> handlers;
    private final Map<String, ToolRiskLevel> declared;

    public ToolRiskLevelConsistencyGuard(@NonNull List<ToolHandler> handlers,
                                          @NonNull Map<String, ToolRiskLevel> declared) {
        this.handlers = List.copyOf(handlers);
        this.declared = Map.copyOf(declared);
    }

    /**
     * Spring 启动回调：<b>在上下文刷新时自动执行校验</b>。
     *
     * <p>⚠️ 抛异常 → 上下文启动失败 → 进程退出。
     * 这是 fail-closed 的落地方式：<b>不一致就不许起</b>。
     */
    @Override
    public void afterPropertiesSet() {
        verify();
    }

    /**
     * 执行校验。
     *
     * @throws IllegalStateException 任一工具的声明级别与代码级别不一致
     */
    public void verify() {
        Map<String, String> mismatches = new LinkedHashMap<>();

        for (ToolHandler handler : handlers) {
            String toolName = handler.toolName();
            ToolRiskLevel codeLevel = handler.riskLevel();
            ToolRiskLevel declaredLevel = declared.get(toolName);
            if (declaredLevel == null) {
                // ⚠️ 未登记不是「不一致」：未登记工具按 WRITE_EXTERNAL 兜底（更严），
                //    属设计内行为，不应阻止启动。
                log.info("工具未在 risk-level-map 中登记，按最严兜底: tool={}, code={}",
                        toolName, codeLevel);
                continue;
            }
            if (declaredLevel != codeLevel) {
                mismatches.put(toolName,
                        "yml 声明=" + declaredLevel + "，代码声明=" + codeLevel);
            }
        }

        if (!mismatches.isEmpty()) {
            String detail = mismatches.entrySet().stream()
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            log.error("⛔ 工具风险级别声明与代码不一致，拒绝启动: {}", detail);
            throw new IllegalStateException(
                    "工具风险级别声明与代码不一致，拒绝启动（fail-closed）: " + detail);
        }

        log.info("工具风险级别声明与代码一致（{} 个工具已核对）", handlers.size());
    }
}
