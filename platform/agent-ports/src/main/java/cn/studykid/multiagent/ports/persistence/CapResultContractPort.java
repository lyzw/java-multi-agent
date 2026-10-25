package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapResultContractDef;

/**
 * 输出契约仓储端口（多级 Agent v1.1 · FR-027 · MA-T16）。
 *
 * <p>⚠️ <b>未发布即不可引用</b>：读取方（含发布期校验器）必须区分
 * 「版本不存在」与「版本存在但未发布」—— 前者是引用写错，后者是引用了草稿。
 * 两者都不可用，但排查方向不同。
 *
 * <p>⚠️ <b>租户隔离</b>：契约是租户级对象，端口实现必须按 {@code tenantId} 过滤，
 * 不得跨租户返回（跨租户存在性也不泄漏）。
 */
public interface CapResultContractPort {

    /**
     * 插入一条契约定义行（<b>幂等</b>）。
     *
     * @param def 契约定义行
     * @return {@code true} 表示本次插入；{@code false} 表示已存在同版本
     */
    boolean insert(@NonNull CapResultContractDef def);

    /**
     * 按固定版本读取。
     *
     * @param tenantId   租户标识
     * @param contractId 契约标识
     * @param version    契约版本号
     * @return 契约定义行；不存在时为空
     */
    @NonNull Optional<CapResultContractDef> find(@NonNull String tenantId,
                                                  @NonNull String contractId,
                                                  int version);

    /**
     * 读取该契约的<b>最新已发布</b>版本。
     *
     * <p>⚠️ 仅供诊断与提示使用：<b>运行期与发布期都不得</b>用它代替固定版本引用
     * —— 那正是漂移的来源。
     *
     * @param tenantId   租户标识
     * @param contractId 契约标识
     * @return 最新已发布版本；无已发布版本时为空
     */
    @NonNull Optional<CapResultContractDef> findLatestPublished(@NonNull String tenantId,
                                                                 @NonNull String contractId);

    /**
     * 读取该契约的<b>最新版本</b>（<b>含草稿</b>）。
     *
     * <p>⚠️ <b>仅用于「分配下一个版本号」</b>：发布流程按
     * 「现有最大版本 + 1」分配新版本号，与 {@code CapAgentDefPort.findLatest} 同口径。
     * 之所以必须<b>含草稿</b>：草稿版本同样占用了版本号，
     * 只看已发布版本会分配出一个<b>与草稿撞号</b>的版本 ——
     * 撞号后 {@code INSERT ... ON CONFLICT DO NOTHING} 会静默丢弃新契约，
     * 表现为「发布成功但契约没生效」。
     *
     * @param tenantId   租户标识
     * @param contractId 契约标识
     * @return 最新版本行（含草稿）；从未登记过时为空
     */
    @NonNull Optional<CapResultContractDef> findLatest(@NonNull String tenantId,
                                                       @NonNull String contractId);
}
