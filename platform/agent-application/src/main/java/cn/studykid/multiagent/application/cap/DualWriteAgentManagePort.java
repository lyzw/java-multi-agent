package cn.studykid.multiagent.application.cap;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.AgentManagePort;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentItem;
import cn.studykid.multiagent.ports.persistence.AgentQueryPort.AgentVersionItem;
import cn.studykid.multiagent.ports.persistence.CapDualWritePort;

/**
 * {@link AgentManagePort} 的双写装饰器（P0-T6）。
 *
 * <p><b>为什么用装饰器而不是在 JDBC 仓储里直接写</b>：
 * 让「旧模型写入」与「新模型双写」在<b>同一个应用服务边界</b>上可见，
 * 而不必改动既有 JDBC 仓储的事务与审计时序
 * （该仓储的审计在提交<b>之后</b>落库，是既有刻意设计，不能被顺带改掉）。
 * 装饰器也让「双写失败不阻断旧路径」这条 fail-soft 契约有唯一落点。
 *
 * <p>⚠️ <b>装饰器不改变旧路径的返回值与异常语义</b>：
 * 旧路径抛出的异常（重名 409、版本冲突 409、Agent 不存在）原样透传。
 */
public class DualWriteAgentManagePort implements AgentManagePort {

    private final AgentManagePort delegate;
    private final CapDualWritePort dualWrite;

    public DualWriteAgentManagePort(@NonNull AgentManagePort delegate,
                                    @NonNull CapDualWritePort dualWrite) {
        this.delegate = delegate;
        this.dualWrite = dualWrite;
    }

    @Override
    public @NonNull AgentItem createAgent(@NonNull String tenantId,
                                           @NonNull String name,
                                           @Nullable String description,
                                           @NonNull String creatorId)
            throws DuplicateAgentNameException {
        // ⚠️ 创建 Agent 本身不产生版本行，故无双写内容。
        AgentItem item = delegate.createAgent(tenantId, name, description, creatorId);
        return item;
    }

    @Override
    public @NonNull AgentVersionItem publishVersion(@NonNull String tenantId,
                                                    @NonNull String agentId,
                                                    @NonNull String definition,
                                                    @NonNull String publisherId) {
        AgentVersionItem published = delegate.publishVersion(
                tenantId, agentId, definition, publisherId);
        // ⚠️ 双写放在<b>旧路径成功之后</b>：
        //    旧路径失败时不得双写（否则会写出一条「已发布」的版本行，
        //    而旧路径根本没有发布成功 —— 两套表示就此分叉）。
        dualWrite.onAgentVersionPublished(
                tenantId, agentId, published.versionNumber(), definition,
                null, null, published.publishedAt());
        return published;
    }
}
