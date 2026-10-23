package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 当前发布指针（§6.3 第 3/5 条 · W-PERM-P5）。
 *
 * <p>⚠️ <b>这是「当前生效配置」的唯一权威来源</b>：
 * 运行时当前配置 = <b>指针指向的</b> PUBLISHED release 的 {@code object_versions}。
 * <b>绝不</b>以 {@code max(config_publish_seq)} 选择，
 * <b>绝不</b>扫描历史行判断当前，<b>绝不</b>用「某对象最新已发布版本行」代替
 * （06 §3.2 第 1 条 / §6.3 第 2 条）。
 *
 * <p>⚠️ <b>为什么必须有它</b>（P0 的最大缺口）：
 * {@code cap_config_release} 只有发布头与 {@code object_versions}，
 * 没有「当前发布是哪一个」这一实体 —— 于是：
 * <ul>
 *   <li>「当前版本」退化为「取最新已发布版本行」，回滚后会解析到<b>已回滚的版本</b>；</li>
 *   <li>CAS 无处落地，并发发布无法线性化（§6.3 第 3/4 条）。</li>
 * </ul>
 *
 * <p>⚠️ <b>指针与发布头分离是刻意的</b>：
 * 指针是<b>可变</b>的（每次发布改一行），发布头是<b>不可变</b>的历史。
 * 把「当前」编码成发布头上的一列，就退化成可变标志（06 §3.2 明令禁止）。
 *
 * @param pointerKey        指针键：{@code PLATFORM} 或 {@code TENANT:<uuid>}
 * @param scope             作用域
 * @param tenantId          租户标识；PLATFORM 作用域为 {@code null}
 * @param currentReleaseId  指针指向的发布头 id；尚未发布时为 {@code null}
 * @param currentPublishSeq CAS 基线（§6.3 第 3 条）；尚未发布时为 {@code null}
 * @param updatedAt         最近一次指针移动时间
 */
public record ConfigPointer(
        @NonNull String pointerKey,
        ConfigRelease.@NonNull Scope scope,
        @Nullable String tenantId,
        @Nullable Long currentReleaseId,
        @Nullable Long currentPublishSeq,
        @NonNull Instant updatedAt) {

    public ConfigPointer {
        if (pointerKey == null || pointerKey.isBlank()) {
            throw new IllegalArgumentException("pointerKey 不能为空");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (scope == ConfigRelease.Scope.TENANT && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT 作用域必须携带 tenantId");
        }
        if (scope == ConfigRelease.Scope.PLATFORM && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM 作用域不得携带 tenantId");
        }
        // ⚠️ 半填状态必须被拒：currentReleaseId 与 currentPublishSeq 必须同时有值或同时为空。
        //    否则读路径无法区分「从未发布过」与「发布记录丢了」——
        //    前者应解析为空（fail-closed），后者是数据损坏，两者不可混同。
        if ((currentReleaseId == null) != (currentPublishSeq == null)) {
            throw new IllegalArgumentException(
                    "currentReleaseId 与 currentPublishSeq 必须同时有值或同时为空");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为空");
        }
    }

    /**
     * 构造尚未发布过的指针（{@code currentReleaseId} / {@code currentPublishSeq} 均为空）。
     *
     * @param scope    作用域
     * @param tenantId 租户标识；PLATFORM 作用域传 {@code null}
     */
    public static @NonNull ConfigPointer empty(ConfigRelease.@NonNull Scope scope,
                                               @Nullable String tenantId) {
        return new ConfigPointer(pointerKey(scope, tenantId), scope, tenantId,
                null, null, Instant.EPOCH);
    }

    /**
     * 构造指针键。
     *
     * <p>⚠️ <b>用单键而不是 (scope, tenant_id) 复合定位</b>：
     * 复合定位里 {@code tenant_id IS NULL} 的比较在 SQL 里恒为 UNKNOWN，
     * 是「CAS 静默不生效」最常见的来源。单键让 CAS 的 WHERE 子句永远是单行定位。
     *
     * @param scope    作用域
     * @param tenantId 租户标识；PLATFORM 作用域传 {@code null}
     */
    public static @NonNull String pointerKey(ConfigRelease.@NonNull Scope scope,
                                             @Nullable String tenantId) {
        if (scope == ConfigRelease.Scope.PLATFORM) {
            return "PLATFORM";
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("TENANT 作用域必须携带 tenantId");
        }
        return "TENANT:" + tenantId;
    }

    /**
     * @return 是否已指向某个发布（{@code false} 表示从未发布过）
     */
    public boolean isPublished() {
        return currentReleaseId != null;
    }

    /**
     * @return 平台级指针键
     */
    public static @NonNull String platformKey() {
        return "PLATFORM";
    }

    /**
     * @return 租户级指针键
     */
    public static @NonNull String tenantKey(@NonNull UUID tenantId) {
        return "TENANT:" + tenantId;
    }
}
