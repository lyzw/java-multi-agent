package cn.studykid.multiagent.ports.persistence;

import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapabilitySnapshot;

/**
 * Run 级能力闭包快照仓储端口（§6.1 / 06 §1.6 · W-PERM-P0-T4）。
 *
 * <p><b>快照的职责</b>（06 §2）：Run 创建时固化「本 Run 依赖的精确版本集 + 闭包指纹」，
 * 使历史可追溯；<b>执行期不得再解析为任意最新版</b>。
 *
 * <p>⚠️ 写入方在 P2（Run 创建时），读取方在 P1/P2（运行期重验）。
 * P0 只提供存储能力。
 */
public interface CapabilitySnapshotPort {

    /**
     * 保存能力闭包快照。
     *
     * <p>幂等：同一 {@code runId} 重复保存以<b>先写入者为准</b>，
     * 不覆盖既有快照（快照一经固化不可变 —— 06 §2「历史可追溯」）。
     *
     * @return {@code true} 表示本次写入成功；{@code false} 表示已存在同 runId 的快照
     */
    boolean save(@NonNull CapabilitySnapshot snapshot);

    /**
     * 按 Run 读取快照。
     *
     * @return 快照；不存在时返回空
     */
    @NonNull Optional<CapabilitySnapshot> find(@NonNull String tenantId, @NonNull String runId);
}
