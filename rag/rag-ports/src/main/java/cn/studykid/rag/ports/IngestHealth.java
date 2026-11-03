package cn.studykid.rag.ports;

/**
 * 摄取链健康快照（V1.0.2 §7.3 / S4-1 告警用）。
 *
 * <p><b>⚠️ 必须是顶层类型，不得嵌套在 {@link IngestJobPort} 内</b>：
 * {@code rag-ports} 的 {@code NestedTypeImportGuardTest} 明令禁止嵌套类型——
 * JDK 21.0.12 的 javac 在「同时导入外层与其嵌套类型」时存在可复现的符号解析缺陷
 * （见 {@code LayoutBlock} 类注释）。首次实现时把它写在端口内部，
 * 该守卫测试立即失败并阻断构建。
 *
 * @param failed       失败的 job 数（**不含** REJECTED+SOURCE_REVOKED 的撤回取消，N2）
 * @param finished     已结束的 job 数（成功+失败+取消）
 * @param pending      待处理（PENDING）数
 * @param oldestPendingMinutes 最老 PENDING 任务的等待分钟数（无则 0）
 * @param takeoversLastHour 近 1 小时被 Sweeper 接管的次数
 */
public record IngestHealth(int failed, int finished, int pending,
                           long oldestPendingMinutes, int takeoversLastHour) {

    /** @return 失败率（无已结束任务时为 0） */
    public double failureRate() {
        return finished <= 0 ? 0.0 : failed / (double) finished;
    }
}
