package cn.studykid.multiagent.application.cap;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.AuthzVersion;
import cn.studykid.multiagent.core.monitoring.AuthzWatermark;
import cn.studykid.multiagent.ports.persistence.AuthzVersionPort;

/**
 * 可编排「第几次读之后开始返回新版本」的授权版本端口。
 *
 * <p>用于精确构造 §9.1 S3b / S3f 的时序：
 * 撤权提交在 Phase A 之后、准入之前 → S3b；
 * 撤权提交在准入之后 → S3f。
 *
 * <p>⚠️ <b>这个竞态无法用「先撤权再调用」构造</b>：
 * 那样 Phase A 就会直接拒绝，根本走不到准入点 ——
 * 测到的就成了「授权判定」，而不是「准入线性化」。
 *
 * <p>⚠️ <b>同一个实例必须被流程的各处共用</b>：
 * 授权版本的三次权威读（Phase A / 校验器 / 准入）发生在同一进程内，
 * 各自 {@code new} 一个实例 = 各自从 1 开始数 → 永远读不到被抬高的版本，
 * 场景会退化「正常放行」，而失败现场看起来像「准入没生效」。
 */
final class BumpingAuthzVersions implements AuthzVersionPort {

    private final AuthzVersionPort delegate;
    private int reads;
    private int bumpAfterRead = -1;
    private long bumpedVersion;

    BumpingAuthzVersions(@NonNull AuthzVersionPort delegate) {
        this.delegate = delegate;
    }

    /**
     * 在第 {@code afterRead} 次读之后返回被抬高的版本。
     *
     * @param afterRead 读次数阈值（读次数 &gt; 该值即返回抬高版本）
     * @param version   被抬高的版本号
     */
    void bumpAfterRead(int afterRead, long version) {
        this.bumpAfterRead = afterRead;
        this.bumpedVersion = version;
    }

    @Override
    public @NonNull Optional<AuthzVersion> find(@NonNull String tenantId,
                                                 @NonNull String principalId) {
        return delegate.find(tenantId, principalId);
    }

    @Override
    public @NonNull AuthzVersion require(@NonNull String tenantId,
                                         @NonNull String principalId) {
        reads++;
        if (bumpAfterRead >= 0 && reads > bumpAfterRead) {
            return new AuthzVersion(tenantId, principalId, bumpedVersion, Instant.EPOCH);
        }
        return delegate.require(tenantId, principalId);
    }

    @Override
    public @NonNull AuthzVersion initialize(@NonNull String tenantId,
                                            @NonNull String principalId) {
        return delegate.initialize(tenantId, principalId);
    }

    @Override
    public long bump(@NonNull String tenantId, @NonNull String principalId) {
        return delegate.bump(tenantId, principalId);
    }

    @Override
    public AuthzWatermark watermark(@NonNull String tenantId) {
        return delegate.watermark(tenantId);
    }
}
