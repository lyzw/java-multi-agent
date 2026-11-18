package cn.studykid.multiagent.ports.agentchat.audit;

import org.jspecify.annotations.NonNull;

/**
 * 审计落盘端口（双写的两个出口：数据库 / 目录）。
 *
 * <p><b>⚠️ 异常语义</b>：实现<b>可以抛异常</b>，由写入器统一吞掉并计数。
 * sink 自己不吞异常是有意的：吞在里层，外层就看不到「某一个 sink 坏了」，
 * 而双写的意义正是「一个坏了另一个还在写」。
 *
 * <p><b>⚠️ 实现不得依赖调用线程的任何上下文</b>：写入发生在后台 worker 线程，
 * 那里没有租户 MDC、没有 SecurityContext、没有请求属性。需要租户就只从记录本身取。
 */
public interface ChatAuditSinkPort {

    /** sink 名称（日志与计数标签）。 */
    @NonNull String name();

    /** 写入一条记录（两种形态各自由实现决定落哪张表 / 哪个目录）。 */
    void write(@NonNull ChatAuditRecord record);
}
