package cn.studykid.multiagent.application.cap;

import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.core.cap.ConfigLifecycleState;
import cn.studykid.multiagent.core.cap.ReasonCode;
import cn.studykid.multiagent.ports.persistence.ConfigLifecyclePort;

/**
 * 生命周期迁移服务（§6.6 / 06 §3 · W-PERM-P5-T4）。
 *
 * <p>⚠️ <b>为什么必须有这一层，而不是让调用方直接用端口</b>：
 * {@link ConfigLifecyclePort} 只提供<b>CAS 迁移</b>（期望原状态匹配才改），
 * 它<b>不判断迁移本身是否合法</b> —— 那是状态机的职责。
 * 若两者之间没有这一层，状态机就成了「摆件」：
 * 非法迁移（草稿直接进已发布、已发布退回草稿、停用对象复活）
 * 会一路落到 DB，被不可变触发器或对齐触发器以
 * <b>裸 SQL 错误</b>的形式炸出来 —— 报错点离真因（「这条迁移根本不合法」）很远，
 * 且错误文案取决于 DB 而非领域语义。
 *
 * <p>⚠️ <b>两类失败的区分（本类的全部要点）</b>：
 * <ul>
 *   <li><b>非法迁移</b>（如 {@code DEPRECATED → PUBLISHED}）→ 抛
 *       {@link ConfigPublishRejectedException}：这是<b>配置/操作错误</b>，
 *       重试没有意义，必须改操作方式；</li>
 *   <li><b>CAS 前置条件不符</b>（原状态已被别人改动）→ 返回 {@code false}：
 *       这是<b>并发竞争</b>，调用方应重新读取状态再决定。</li>
 * </ul>
 * 把两者混为一谈，会让「停用一个已停用对象」这种明确的操作错误
 * 被当成「重试一下说不定就成了」。
 *
 * <p>⚠️ <b>「发布」不是状态迁移</b>：
 * 概念图上 {@code VALIDATED → PUBLISHED} 是合法边，但它<b>不能</b>通过
 * 改状态列实现 —— 到达 PUBLISHED 的唯一途径是<b>发布新版本行</b>
 * （{@code published_at} 非空，由对齐触发器置为 PUBLISHED）+ 切换当前指针（§6.3）。
 * 若允许改状态列，一个从未发布过的草稿就能自称已发布，
 * 而它<b>不在任何发布头的 object_versions 里</b> ——
 * 授权路径读指针时根本找不到它，「未发布不可启用」反而被绕过。
 */
public class ConfigLifecycleService {

    private final ConfigLifecyclePort port;
    private final ConfigLifecycleStateMachine machine;

    public ConfigLifecycleService(@NonNull ConfigLifecyclePort port,
                                  @NonNull ConfigLifecycleStateMachine machine) {
        this.port = port;
        this.machine = machine;
    }

    /**
     * 执行一次生命周期迁移。
     *
     * @param type         对象类型标识（{@code tool} / {@code skill} / {@code mcp_server} / {@code agent}）
     * @param tenantId     租户标识；仅 {@code agent} 需要
     * @param objectId     对象逻辑 id
     * @param version      版本号
     * @param expectedFrom 期望的原状态（CAS 前置条件）
     * @param to           目标状态
     * @return {@code true} 表示迁移成功；{@code false} 表示原状态不符（并发竞争）
     * @throws ConfigPublishRejectedException 迁移本身非法
     */
    public boolean transition(@NonNull String type,
                              @Nullable String tenantId,
                              @NonNull String objectId,
                              int version,
                              @NonNull ConfigLifecycleState expectedFrom,
                              @NonNull ConfigLifecycleState to) {
        if (!machine.canTransition(expectedFrom, to)) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "lifecycleState",
                    "非法的生命周期迁移: " + expectedFrom + " → " + to
                            + "（对象 " + objectId + " v" + version + "）。允许的迁移见 §6.6："
                            + "DRAFT→VALIDATED→PUBLISHED→DEPRECATED，VALIDATED→DRAFT 为校验失败回退边，"
                            + "DEPRECATED 为终态");
        }
        if (to == ConfigLifecycleState.PUBLISHED) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "lifecycleState",
                    "PUBLISHED 不能通过修改状态列获得（对象 " + objectId + " v" + version + "）："
                            + "发布 = 发布新版本行 + 切换当前发布指针（§6.3），"
                            + "而不是把一个从未发布的版本行改称已发布 —— "
                            + "后者会让未发布对象自称已发布而绕过「未发布不可启用」（§6.6）");
        }
        return port.transition(type, tenantId, objectId, version, expectedFrom, to);
    }

    /**
     * 读取对象版本行当前的生命周期状态。
     *
     * @param type     对象类型标识
     * @param tenantId 租户标识；仅 {@code agent} 需要
     * @param objectId 对象逻辑 id
     * @param version  版本号
     * @return 当前状态；对象不存在时为 {@link Optional#empty()}
     */
    @NonNull
    public Optional<ConfigLifecycleState> stateOf(@NonNull String type,
                                                  @Nullable String tenantId,
                                                  @NonNull String objectId,
                                                  int version) {
        return port.stateOf(type, tenantId, objectId, version);
    }
}
