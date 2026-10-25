package cn.studykid.multiagent.core.cap;

import java.time.Instant;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 委派输出契约的定义行（06 变更补充 v1.1 · FR-027 · MA-T16）。
 *
 * <p>⚠️ <b>契约是配置对象，不是运行期数据</b>：它描述「子 Agent 的输出应该长什么样」，
 * 发布期固化、运行期只读。子 Agent 的**实际输出**属于不可信运行数据，
 * 两者绝不能混在一张表里 —— 混了就会出现「运行数据覆盖契约定义」。
 *
 * <p>⚠️ <b>必须固定版本</b>：Agent 的 {@code resultContractRef} 形如 {@code id@version}，
 * 禁止 {@code latest} 漂移别名 —— 与子 Agent 引用同口径。
 * 契约改了就是新版本，旧版本仍可被历史 Run 按快照引用。
 *
 * @param tenantId         租户标识
 * @param contractId       契约标识（逻辑 id）
 * @param version          契约版本号（固定引用）
 * @param schemaJson       契约定义（JSON Schema 文本）
 * @param configFingerprint 内容指纹（判定「契约变了没有」）
 * @param schemaVersion    行格式版本
 * @param createdAt        创建时间
 * @param publishedAt      发布时间；{@code null} = 草稿（<b>不可被引用</b>）
 */
public record CapResultContractDef(
        @NonNull String tenantId,
        @NonNull String contractId,
        int version,
        @NonNull String schemaJson,
        @NonNull String configFingerprint,
        int schemaVersion,
        @NonNull Instant createdAt,
        @Nullable Instant publishedAt) {

    public CapResultContractDef {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("contractId 不能为空");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须为正数，收到: " + version);
        }
        if (schemaJson == null || schemaJson.isBlank()) {
            // ⚠️ 空契约 ≠ 任意结构：契约必须**显式**声明「允许任意」，
            //    否则「填坏了」与「没填」在存储层就分不开。
            throw new IllegalArgumentException("schemaJson 不能为空");
        }
        if (configFingerprint == null || configFingerprint.isBlank()) {
            throw new IllegalArgumentException("configFingerprint 不能为空");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt 不能为空");
        }
    }

    /**
     * @return 是否为已发布版本（草稿不可被 Agent 引用）
     */
    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * @return 固定版本引用（{@code id@version}）
     */
    public @NonNull String ref() {
        return contractId + "@" + version;
    }
}
