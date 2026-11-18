package cn.studykid.multiagent.ports.agentchat.audit;

import org.jspecify.annotations.NonNull;

/**
 * 审计写入端口（异步有界队列 + 双写扇出）。
 *
 * <p><b>⚠️ fail-open 铁律</b>：{@link #submit} 绝不阻塞、绝不抛异常。
 * 队列满即丢弃并计数——「审计记不全」是可接受损失，「业务请求因审计变慢/变失败」不是。
 *
 * <p><b>⚠️ {@code async=false} 的语义</b>：在调用线程直接写，仍然吞异常。
 * 它是给排障 / 低流量环境用的（写完即确定），不是给生产热路径用的。
 *
 * <p><b>⚠️ 计数必须可观测</b>：三个计数（提交 / 丢弃 / sink 失败）是唯一能证明
 * 「审计真的在记」的证据。缺失时表现为「页面正常、库里没数据」，最容易被误判成功能没开。
 */
public interface ChatAuditWriterPort {

    /** 提交一条完整记录（非阻塞；队列满则丢弃并计数）。 */
    void submit(@NonNull ChatAuditRecord record, boolean async);

    /** 已提交条数。 */
    long submittedTotal();

    /** 因队列满被丢弃条数。 */
    long droppedTotal();

    /** sink 写入失败次数（含单 sink 失败）。 */
    long sinkErrorTotal();

    /** 至少一个 sink 写成功的条数。 */
    long writtenTotal();
}
