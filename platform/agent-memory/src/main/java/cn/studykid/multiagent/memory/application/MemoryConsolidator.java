package cn.studykid.multiagent.memory.application;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.memory.port.MemoryPort;
import cn.studykid.multiagent.ports.persistence.ResultQueryPort;

/**
 * 跨 Run 记忆整合（P1）。
 *
 * <p><b>⚠️⚠️ 三条"不得"（每一条都对应一个曾被忽略的事故形态）</b>：
 * <ol>
 *   <li><b>不得</b>从模型输出派生记忆 —— 只认 {@code weakness_record}
 *       （经 {@link ResultQueryPort#findResult} 读出）。模型说"我记住了"
 *       不构成证据；证据必须由 {@code record_weakness} 工具经
 *       完整闸门写入（幂等、审计、风险分级）。</li>
 *   <li><b>不得</b>在 Run 失败/取消时整合 —— 失败的 Run 里
 *       {@code weakness_record} 可能只写了一半（工具调用成功、随后 Run 超时），
 *       把半程结果沉淀成"长期弱点"等于用失败过程污染长期记忆。</li>
 *   <li><b>不得</b>因整合失败让 Run 失败 —— 此时 Run 已经<b>提交成功</b>，
 *       再改状态就是伪造结果。整合失败只打 WARN。</li>
 * </ol>
 *
 * <p><b>⚠️ 为什么以 Run 为单位而不是以会话为单位</b>：
 * {@code weakness_record} 的幂等键是 {@code (tenant, student, run, kp)}，
 * 天然就是"一个 Run 一次证据"。以 Run 为单位整合，幂等判据
 * （{@code last_run_id}）才有确切含义；以"会话"为单位则无法判断
 * 两次整合是不是同一次。
 */
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    /** 允许沉淀记忆的 Run 状态。 */
    private static final String STATE_SUCCEEDED = "SUCCEEDED";

    private final MemoryPort memoryPort;
    private final ResultQueryPort resultQueryPort;

    public MemoryConsolidator(@NonNull MemoryPort memoryPort,
                              @NonNull ResultQueryPort resultQueryPort) {
        this.memoryPort = memoryPort;
        this.resultQueryPort = resultQueryPort;
    }

    /**
     * 把该 Run 的证据整合进记忆。
     *
     * @param tenantId  租户
     * @param runId     Run 标识
     * @param studentId 学生标识；{@code null}/空白 = <b>不整合</b>
     * @return 受影响条目数；跳过或失败返回 0（<b>恒不抛异常</b>）
     */
    public int consolidate(@NonNull String tenantId,
                           @NonNull String runId,
                           @Nullable String studentId) {
        if (studentId == null || studentId.isBlank()) {
            // ⚠️ 没有主体就没有可写的记忆行（student_memory.student_id 非空 + FK）。
            //    不是错误：通用问答 Agent 不关联学生。
            return 0;
        }
        try {
            ResultQueryPort.@Nullable RunResult result =
                    resultQueryPort.findResult(tenantId, runId);
            if (result == null) {
                // ⚠️ 查不到时**不整合**（而不是"当作没有弱点"直接跳过建行）：
                //    查不到可能意味着"刚提交、读侧还没可见"，也可能意味着
                //    "租户不符"。两种情况下写入都是猜测。
                log.debug("Run 结果不可读，跳过记忆整合: tenant={}, run={}", tenantId, runId);
                return 0;
            }
            if (!STATE_SUCCEEDED.equals(result.state())) {
                // ⚠️ 见类注释第 2 条：失败/取消的 Run 不沉淀。
                log.debug("Run 非成功终态，跳过记忆整合: run={}, state={}",
                        runId, result.state());
                return 0;
            }
            if (result.weaknesses().isEmpty()) {
                return 0;
            }

            // ⚠️ 真正的证据读取在端口实现内完成（{@code weakness_record} 按 run_id 查）。
            //    这里调用 {@code findResult} 的唯一目的是**确认该 Run 值得沉淀**，
            //    并让"非成功不整合"这条判断有据可依 —— 端口实现不重复判断状态。
            int affected = memoryPort.consolidate(tenantId, studentId, runId);
            if (affected > 0) {
                log.info("Run 证据已沉淀为记忆: tenant={}, student={}, run={}, 条目={}",
                        tenantId, studentId, runId, affected);
            }
            return affected;
        } catch (RuntimeException e) {
            // ⚠️ 见类注释第 3 条：Run 已经提交成功，整合失败不得改状态。
            log.warn("记忆整合失败（不影响 Run 结果）: tenant={}, student={}, run={}",
                    tenantId, studentId, runId, e);
            return 0;
        }
    }
}
