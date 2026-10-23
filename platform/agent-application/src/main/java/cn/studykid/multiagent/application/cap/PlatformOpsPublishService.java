package cn.studykid.multiagent.application.cap;

import java.time.Clock;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ConfigPublishResult;
import cn.studykid.multiagent.core.cap.ConfigRelease;
import cn.studykid.multiagent.core.cap.ConfigRollbackCommand;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.CapAgentDefPort;
import cn.studykid.multiagent.ports.persistence.CapMcpServerDefPort;
import cn.studykid.multiagent.ports.persistence.CapSkillDefPort;
import cn.studykid.multiagent.ports.persistence.CapToolDefPort;
import cn.studykid.multiagent.ports.persistence.ConfigPointerPort;
import cn.studykid.multiagent.ports.persistence.ConfigPublishTransaction;
import cn.studykid.multiagent.ports.persistence.ConfigReleasePort;
import cn.studykid.multiagent.ports.persistence.DecisionAuditPort;

/**
 * 平台运维角色的配置发布入口（PLATFORM 作用域专用）。
 *
 * <p>⚠️ <b>为什么必须是独立于 {@link ConfigPublishService} 的入口</b>：
 * 平台运维角色的数据库连接带 {@code BYPASSRLS}，不受任何 RLS 策略约束。
 * 若把它接到应用侧那条「按 scope 自动选池」的通路上，
 * <b>调用方只要把 scope 写成 PLATFORM 就拿到了运维凭据</b> ——
 * DB 层的越权防线被降级成应用层的一行判断，
 * 而「谁有权发平台级配置」这个判断在当前版本里<b>尚不存在</b>
 * （管理端点未接，见 P6 登记限制 7）。
 *
 * <p>因此这里把两条通道彻底分开：
 * <ul>
 *   <li>{@link ConfigPublishService} —— 应用角色连接，PLATFORM 作用域会被 RLS 拒写；</li>
 *   <li>本类 —— 平台运维角色连接，只接受 PLATFORM 作用域。</li>
 * </ul>
 * 两条通道<b>没有共享的凭据</b>，也不存在「一个服务对象按 scope 换池」的分叉。
 *
 * <p>⚠️ <b>调用前置条件（硬性）</b>：
 * 本类的调用方<b>必须</b>已经完成<b>平台运维身份认证</b>。
 * 本类只负责「用哪条数据库通道写」，<b>不负责</b>「谁有资格发平台级配置」——
 * 后者是接入方（未来的管理端点）的责任，缺失时<b>不得</b>接入本类。
 * 当前仓库尚未接入任何管理端点，故本类暂无生产调用方，
 * 这是<b>刻意</b>的：先有凭据通道，再有认证，二者不可颠倒。
 *
 * <p>⚠️ <b>作用域闸门</b>：本类<b>只</b>接受 PLATFORM 作用域。
 * 用它发租户作用域的发布会被立即拒绝 ——
 * 运维凭据绕过 RLS，用它写租户数据等于开一个跨租户写入的后门。
 *
 * <p>⚠️ <b>发布与回滚语义完全复用 {@link ConfigPublishService}</b>：
 * 原子发布（版本行 + release + 指针 CAS 同事务）、CAS 变基重试、
 * 回滚不恢复已撤权 —— 平台作用域与租户作用域<b>共用同一套协议</b>，
 * 差别只在「用哪条数据库通道写」。刻意不复刻一遍发布逻辑：
 * 两份实现迟早会漂移，而漂移出来的差异只在平台作用域生效。
 */
public class PlatformOpsPublishService {

    private final ConfigPublishService delegate;

    /**
     * @param platformOpsTx 平台运维角色的发布事务（<b>不得</b>传入应用角色的事务）
     * @param pointers      当前发布指针端口
     * @param releases      发布头端口
     * @param toolDefs      Tool 定义端口
     * @param skillDefs     Skill 定义端口
     * @param serverDefs    MCP Server 定义端口
     * @param agentDefs     Agent 定义端口
     * @param audit         四字段决策日志端口
     * @param gate          配置期校验门禁
     * @param clock         时钟
     */
    public PlatformOpsPublishService(@NonNull ConfigPublishTransaction platformOpsTx,
                                     @NonNull ConfigPointerPort pointers,
                                     @NonNull ConfigReleasePort releases,
                                     @NonNull CapToolDefPort toolDefs,
                                     @NonNull CapSkillDefPort skillDefs,
                                     @NonNull CapMcpServerDefPort serverDefs,
                                     @NonNull CapAgentDefPort agentDefs,
                                     @NonNull DecisionAuditPort audit,
                                     @NonNull ConfigValidationGate gate,
                                     @NonNull Clock clock) {
        this.delegate = new ConfigPublishService(platformOpsTx, pointers, releases, toolDefs,
                skillDefs, serverDefs, agentDefs, audit, gate, clock);
    }

    /**
     * 平台级原子发布（§6.3 第 1/3/4 条）。
     *
     * @param command 发布命令；<b>作用域必须为 PLATFORM</b>
     * @return 发布结果
     * @throws ConfigPublishRejectedException 作用域不是 PLATFORM，或配置期门禁不通过
     * @throws cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException
     *         平台运维角色未接入（fail-closed）
     * @throws ConfigPublishConflictException 变基重试仍失败（并发发布持续冲突）
     */
    @NonNull
    public ConfigPublishResult publish(@NonNull ConfigPublishCommand command) {
        requirePlatformScope(command.scope(), "publish");
        return delegate.publish(command);
    }

    /**
     * 平台级回滚（§6.3 第 6 条 / 06 §3.2）。
     *
     * @param command 回滚命令；<b>作用域必须为 PLATFORM</b>
     * @return 发布结果
     * @throws ConfigPublishRejectedException 作用域不是 PLATFORM，或回滚目标非法
     * @throws cn.studykid.multiagent.ports.persistence.PlatformOpsUnavailableException
     *         平台运维角色未接入（fail-closed）
     * @throws ConfigPublishConflictException 当前指针已被他人推进
     */
    @NonNull
    public ConfigPublishResult rollback(@NonNull ConfigRollbackCommand command) {
        requirePlatformScope(command.scope(), "rollback");
        return delegate.rollback(command);
    }

    /**
     * 作用域闸门。
     *
     * <p>⚠️ 用 {@code RESOURCE_UNKNOWN} 与 {@link ConfigValidationGate} 的作用域一致性
     * 校验保持同一原因码族：两者都是「这个发布命令的作用域组合不合法」。
     */
    private static void requirePlatformScope(ConfigRelease.@NonNull Scope scope,
                                             @NonNull String operation) {
        if (scope != ConfigRelease.Scope.PLATFORM) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "scope",
                    "平台运维发布入口只接受 PLATFORM 作用域，收到: scope=" + scope
                            + "（操作=" + operation + "）。"
                            + "租户作用域的发布请走应用角色的发布通道；"
                            + "用运维凭据写租户数据会绕过 RLS 的跨租户防线。");
        }
    }
}
