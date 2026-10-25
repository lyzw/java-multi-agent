package cn.studykid.multiagent.worker.execute;

import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.application.orchestration.AgentOrchestrationParser;
import cn.studykid.multiagent.core.cap.CapAgentDef;
import cn.studykid.multiagent.core.orchestration.AgentOrchestration;
import cn.studykid.multiagent.core.orchestration.DelegationToolSchema;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;

/**
 * 委派工具 Schema 的装配器（多级 Agent v1.1 · 第二十批 · FR-035）。
 *
 * <p>⚠️ <b>存在的唯一理由：让模型「看得见」委派能力</b>。
 * 修复前委派工具只出现在闸门分支里，<b>从未进入任何模型的工具表</b> ——
 * 于是运行期模型<b>物理上不可能</b>发起委派：
 * 多级 Agent 的守卫、预算、账本、契约全都齐备，却<b>没有触发点</b>。
 *
 * <p>⚠️ <b>为什么必须显式装配</b>：委派<b>不是</b>普通工具，
 * 它<b>不进</b> Skill→Tool 能力闭包（进了闭包就等于给每个 Agent 都发一把委派枪），
 * 因此闭包解析<b>永远给不出</b>它。
 *
 * <p>⚠️ <b>根路径与子路径共用这一份实现</b>：
 * 两处各写一份必然漂移，而漂移的后果是
 * 「根能委派、子不能」（或反之）—— 那正是本项目反复消除的「两套语义」。
 */
public class DelegationToolSchemaAssembler {

    private static final Logger log = LoggerFactory.getLogger(
            DelegationToolSchemaAssembler.class);

    private final CapAgentDefPort agentDefs;

    public DelegationToolSchemaAssembler(@NonNull CapAgentDefPort agentDefs) {
        this.agentDefs = agentDefs;
    }

    /**
     * 装配指定 Agent 的委派工具 Schema。
     *
     * <p>⚠️ <b>读不到编排声明 → 不装配</b>（fail-closed）：
     * 不知道有没有委派能力时<b>宁可不给</b>，
     * 也不凭猜测给一把委派枪 —— 多给了等于让模型去试探不该碰的边界。
     *
     * <p>⚠️ <b>编排声明非法 → 不装配</b>（不抛）：
     * 声明非法时闸门会 fail-closed 拒绝委派（配置缺陷本应被发布期拦住），
     * 但<b>模型侧不必因此整个不可用</b> —— 该 Agent 的其它工具照常工作。
     *
     * @param tenantId     租户标识
     * @param agentId      Agent 标识
     * @param agentVersion 该 Agent 的<b>固定版本号</b>
     * @return 委派工具 Schema（键为工具名）；该 Agent 无委派能力时返回空
     */
    public @NonNull Map<String, Object> assemble(@NonNull String tenantId,
                                                  @NonNull String agentId,
                                                  int agentVersion) {
        Optional<CapAgentDef> def;
        try {
            def = agentDefs.find(tenantId, agentId, agentVersion);
        } catch (RuntimeException e) {
            log.warn("Agent 定义读取失败，不装配委派工具: agent={}, version={}",
                    agentId, agentVersion, e);
            return Map.of();
        }
        if (def.isEmpty()) {
            return Map.of();
        }
        AgentOrchestration orchestration;
        try {
            orchestration = AgentOrchestrationParser.parse(def.get().orchestration());
        } catch (RuntimeException e) {
            // ⚠️ 声明非法 → 不给委派工具（闸门侧同样 fail-closed 拒绝委派）。
            log.warn("编排声明非法，不装配委派工具: agent={}, version={}",
                    agentId, agentVersion, e);
            return Map.of();
        }
        return DelegationToolSchema.forOrchestration(orchestration)
                .map(DelegationToolSchema::render)
                .orElseGet(Map::of);
    }
}
