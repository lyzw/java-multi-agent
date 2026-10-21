package cn.studykid.multiagent.application.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.OverrideEntry;

/**
 * 生效配置解析器（P4-D15 / F6 · C3「写入端点 + 生效机制」的生效侧）。
 *
 * <p><b>⚠️ 合并语义</b>：{@code 生效值 = yml 默认值 ⊕ DB 覆盖值}，
 * 同键以 DB 为准。<b>DB 无覆盖不是错误</b>——回落 yml 默认，
 * 这让「删除覆盖」天然等于「回到出厂设置」，无需额外机制。
 *
 * <p><b>⚠️ 为什么不接进 Spring Environment</b>：那样配置会变成全局可变状态，
 * 与 {@link RunConfigSnapshotFactory} 的「Run 创建时固化快照」冲突——
 * 同一 Run 前后行为不一致正是 R-021 要消除的缺陷。
 * 本类保持「读取时合并」，与快照隔离同构。
 *
 * <p><b>⚠️ 无本地缓存</b>：每次解析都读一次 DB。
 * 这让多实例天然一致（无需同步机制），代价是每次快照多一次查询。
 * 若将来要加缓存，<b>必须</b>同时处理失效，否则会出现
 * 「实例 A 已生效、实例 B 仍用旧值」的分裂（P4-D15 §6 L-3）。
 */
public final class EffectiveConfigResolver {

    private final ConfigOverridePort overrides;

    public EffectiveConfigResolver(@NonNull ConfigOverridePort overrides) {
        this.overrides = overrides;
    }

    /**
     * 解析某租户的生效配置。
     *
     * @param tenantId   租户
     * @param ymlDefaults yml 声明的默认值（键名 → 文本值），来自启动期 {@code @Value} 注入
     * @return 生效值 + 来源标注
     */
    public @NonNull Effective resolve(@NonNull String tenantId,
                                      @NonNull Map<String, String> ymlDefaults) {
        Map<String, OverrideEntry> dbValues = overrides.overridesFor(tenantId);
        Map<String, Resolved> out = new LinkedHashMap<>();
        // 遍历 yml 默认值而非 DB 覆盖：只关心「声明过的键」，
        // DB 里的未知键不应凭空出现在生效配置中（防目录漂移）。
        for (Map.Entry<String, String> e : ymlDefaults.entrySet()) {
            OverrideEntry ov = dbValues.get(e.getKey());
            if (ov != null) {
                out.put(e.getKey(), new Resolved(e.getKey(), ov.value(), "db_override"));
            } else {
                out.put(e.getKey(), new Resolved(e.getKey(), e.getValue(), "yml"));
            }
        }
        return new Effective(tenantId, Map.copyOf(out));
    }

    /** 单条生效配置。 */
    public record Resolved(@NonNull String key,
                           @NonNull String value,
                           /** {@code db_override} 或 {@code yml} */
                           @NonNull String source) {
    }

    /** 某租户的生效配置集合。 */
    public record Effective(@NonNull String tenantId,
                            @NonNull Map<String, Resolved> values) {

        /** 取生效值；无此键返回 null（<b>不</b>返回默认值——调用方须显式处理缺失）。 */
        public @Nullable String get(@NonNull String key) {
            Resolved r = values.get(key);
            return r == null ? null : r.value();
        }

        /** 取生效值，缺失时抛（fail-closed：调用方拿不到值就不该继续）。 */
        public @NonNull String require(@NonNull String key) {
            String v = get(key);
            if (v == null) {
                throw new ConfigResolutionException(key, null, "配置缺失且无覆盖可用（fail-closed）");
            }
            return v;
        }
    }
}