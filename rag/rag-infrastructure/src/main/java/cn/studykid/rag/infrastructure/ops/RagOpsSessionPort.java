package cn.studykid.rag.infrastructure.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 平台全局语料运维会话（RAG-D11 §1.3 / 第二刀）。
 *
 * <p><b>为什么需要它</b>：应用角色 {@code rag_app} 的写策略是
 * {@code tenant_id = rag_current_tenant() AND scope='TENANT'}，
 * <b>按设计写不出全局行</b>——那条策略就是越权闸门本身（反证 P4 钉着它）。
 * 因此「平台在控制台维护全局语料」只能走一条<b>独立角色 + 独立连接池</b>的通道，
 * 把能力物理限长在一个入口上，而不是去放宽所有租户共用的 {@code rag_app}。
 *
 * <p><b>方法名里刻意写死「Global」</b>：本端口<b>不提供</b>任何接收租户标识的形式。
 * 会话内的 SQL 以 {@code rag_global_tenant_id()} 字面函数收口（不是绑定参数），
 * 所以调用方连「把别的桶写进去」这句话都拼不出来。
 * 若将来确实需要跨租户写，那是另一条通道、另一次评审，不在这个接口上长。
 *
 * <p><b>⚠️ 与 {@code RagStartupSelfCheck} 的关系</b>：{@code rag_ops} 带 BYPASSRLS，
 * 一旦被误配成主数据源角色，租户隔离会整体失效；自检因此会在启动时
 * 断言运维角色与主数据源角色<b>不是同一个</b>。本接口的实现也必须
 * 只被运维通道使用——它不导出 {@code javax.sql.DataSource} 类型的 bean，
 * 正是为了让「顺手注入一个 DataSource」在装配层就做不到。
 */
public interface RagOpsSessionPort {

    /**
     * 通道是否已接入（配置齐备）。
     *
     * <p>⚠️ 这只用于<b>可读性判定</b>（如健康检查、能力清单）。
     * 业务代码不要写「未接入就跳过写入」——那会把治理动作伪装成成功。
     */
    boolean isAvailable();

    /**
     * 在一条全局语料运维会话内执行动作。
     *
     * <p>窗口之外的任何连接请求都会被拒（见 {@code GuardedRagOpsDataSource}），
     * 所以窗口必须由本方法开启、并在此方法返回时关闭；
     * 不要把回调里拿到的模板存成字段——那等于把闸门拆了还留在手里。
     *
     * @param action 窗口内执行的动作
     * @param <T>    返回值类型
     * @return 动作的返回值
     * @throws RagOpsUnavailableException 通道未接入（调用方应呈现为 503，绝不回落应用角色）
     */
    <T> T inGlobalCorpusSession(RagOpsAction<T> action);

    /**
     * 运维会话内的动作。
     *
     * @param <T> 返回值类型
     */
    @FunctionalInterface
    interface RagOpsAction<T> {

        /**
         * @param jdbcTemplate          绑定<b>运维连接池</b>的模板（仅窗口内可用）
         * @param transactionTemplate   同一数据源的事务模板；调用方自己决定事务边界
         *                              （重索引是分页事务，登记/校验是单事务）
         * @return 动作结果
         */
        T run(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate);
    }
}
