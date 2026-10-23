package cn.studykid.multiagent.infrastructure.persistence.cap;

/**
 * 平台运维会话守卫（包内可见）。
 *
 * <p>⚠️ <b>为什么需要它</b>：
 * 平台运维数据源持有 {@code BYPASSRLS} 属性 —— 它的连接<b>不受任何 RLS 策略约束</b>。
 * 一旦这个数据源被装配到业务读路径（例如「当前生效配置」的解析），
 * 租户隔离就<b>整体失效</b>：任何租户都能读到其他租户的行。
 * 而这类误用在代码审查时极难一眼看出（多一个 {@code DataSource} 参数而已）。
 *
 * <p>⚠️ <b>因此本守卫把「能不能用」与「什么时候能用」绑死</b>：
 * 只有在 <b>PLATFORM 作用域发布事务</b>这一段明确的时间窗内，
 * 运维数据源才允许交出连接；窗口之外一律拒绝，
 * 且拒绝时抛出的异常里直接写明「只有平台级发布链路可以使用」。
 *
 * <p>这不是「文档约定」，是<b>运行期强制</b>：
 * 误接线会在第一次调用时立刻炸，而不是等到有人发现越权读。
 */
interface PlatformOpsSessionGuard {

    /**
     * 要求当前线程处于平台运维会话内。
     *
     * @throws IllegalStateException 若不在会话内
     */
    void requireActiveSession();
}
