package cn.studykid.multiagent.core.cap;

import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 回滚命令（§6.3 第 6 条 / 06 §3.2 · W-PERM-P5）。
 *
 * <p>⚠️ <b>回滚 = 创建一条新的 PUBLISHED release</b>，其 {@code object_versions}
 * 指向目标（旧的）版本集合；<b>不覆盖、不删除</b>任何历史版本行或历史发布记录。
 * 被回滚掉的那条发布记录标记为 {@code ROLLED_BACK}（<b>仅审计旁路</b>，
 * 不参与当前版本解析，§6.3 第 7 条）。
 *
 * <p>⚠️ <b>回滚绝不恢复已撤销的主体授权</b>：
 * {@code cap_authz_version} 是独立于配置发布的权威源（§5.5 / 06 §1.5），
 * 配置回滚<b>不得</b>回退它，也<b>不得</b>复活任何已撤销的审批。
 * 若把两者耦合，「回滚配置」就会变成「恢复权限」——
 * 那是一条直接的越权路径。
 *
 * @param scope               作用域
 * @param tenantId            租户标识；PLATFORM 作用域为 {@code null}
 * @param targetReleaseId     回滚目标（要恢复到的发布记录 id）
 * @param expectedPublishSeq  CAS 基线；{@code null} 表示「期望尚未发布过」
 * @param requesterId         申请人
 * @param approverId          审批人（双人复核）
 * @param aggregateFingerprint 聚合指纹；{@code null} 表示沿用目标发布的指纹
 * @param effectiveAt         生效时间
 */
public record ConfigRollbackCommand(
        ConfigRelease.@NonNull Scope scope,
        @Nullable String tenantId,
        long targetReleaseId,
        @Nullable Long expectedPublishSeq,
        @Nullable String requesterId,
        @Nullable String approverId,
        @Nullable String aggregateFingerprint,
        @Nullable Instant effectiveAt) {

    public ConfigRollbackCommand {
        if (scope == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        if (scope == ConfigRelease.Scope.TENANT && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT 作用域必须携带 tenantId");
        }
        if (scope == ConfigRelease.Scope.PLATFORM && tenantId != null) {
            throw new IllegalArgumentException("PLATFORM 作用域不得携带 tenantId");
        }
        if (targetReleaseId <= 0) {
            throw new IllegalArgumentException("targetReleaseId 必须为正数，收到: " + targetReleaseId);
        }
        if (expectedPublishSeq != null && expectedPublishSeq < 0) {
            throw new IllegalArgumentException("expectedPublishSeq 不得为负数");
        }
    }
}
