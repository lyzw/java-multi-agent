package cn.studykid.rag.infrastructure.ops;

/**
 * 「当前线程是否处在运维会话窗内」的判据（包内可见，仅供
 * {@link GuardedRagOpsDataSource} 与 {@link RagOpsDataSourceProvider} 之间使用）。
 *
 * <p>单独成一个一方法接口的原因：让「守卫」与「被守卫的数据源」之间的依赖
 * 是抽象的，架构测试才能钉住「只有 provider 能改窗口状态」。
 */
@FunctionalInterface
interface RagOpsSessionGuard {

    /**
     * @throws IllegalStateException 当前线程不在运维会话窗内
     */
    void requireActiveSession();
}
