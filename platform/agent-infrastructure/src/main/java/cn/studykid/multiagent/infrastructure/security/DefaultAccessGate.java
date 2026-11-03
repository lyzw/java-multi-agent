package cn.studykid.multiagent.infrastructure.security;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.studykid.multiagent.core.security.Action;
import cn.studykid.multiagent.core.security.Decision;
import cn.studykid.multiagent.core.security.ResourceRef;
import cn.studykid.multiagent.core.security.ScopeFilter;
import cn.studykid.multiagent.core.tenant.SubjectContext;

/**
 * 默认门禁实现 —— {@code AccessGate} 外观，委托 {@link PolicyEngine} 决策（12 文 §2.4 / D3）。
 *
 * <p><b>fail-closed 纪律</b>：
 * <ul>
 *   <li>主体解析失败 / 为 {@code null} → {@link AccessDeniedException}；</li>
 *   <li>决策为 Deny → 抛 {@link AccessDeniedException}；</li>
 *   <li>决策过程任何异常 → 抛 {@link AccessDeniedException}（绝不回落放行，对应 R5）；</li>
 *   <li>仅 Permit → 返回 {@link ScopeFilter} 供查询渲染。</li>
 * </ul>
 *
 * <p><b>决策日志（D6）</b>：每次判定（Permit / Deny / 异常）都经
 * {@link AuthzDecisionRecorder} 落记录（默认 {@link NoOpAuthzDecisionRecorder} 无副作用；
 * P2 翻转为 {@link JdbcAuthzDecisionLog} 持久化到 {@code platform.authz_decision_log}）。
 *
 * <p>⚠️ <b>日志失败绝不改变判定结果</b>（见 {@link #record}）：
 * <ul>
 *   <li>Permit 路径上若让日志异常冒出来，「放行」会变成 5xx —— 一次审计表抖动即全站故障；</li>
 *   <li>Deny 路径上若让日志异常冒出来，原始 {@code reasonCode} 被替换成内部异常，
 *       调用方再也分不清 403 与 500。</li>
 * </ul>
 * fail-closed 针对的是<b>判定</b>，不是<b>记录判定</b> —— 两者混淆会把可用性搭进去。
 */
public final class DefaultAccessGate implements AccessGate {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAccessGate.class);

    private final SubjectContextResolver resolver;
    private final PolicyEngine engine;
    private final AuthzDecisionRecorder recorder;

    public DefaultAccessGate(@NonNull SubjectContextResolver resolver, @NonNull PolicyEngine engine) {
        this(resolver, engine, NoOpAuthzDecisionRecorder.INSTANCE);
    }

    public DefaultAccessGate(@NonNull SubjectContextResolver resolver,
                             @NonNull PolicyEngine engine,
                             @NonNull AuthzDecisionRecorder recorder) {
        this.resolver = resolver;
        this.engine = engine;
        this.recorder = recorder;
    }

    @Override
    public @NonNull ScopeFilter authorize(@NonNull Action action, @NonNull ResourceRef resource) {
        return decideOrThrow(resolveSubject(), action, resource);
    }

    @Override
    public @NonNull ScopeFilter authorizeCollection(@NonNull Action action, @NonNull String resourceType) {
        return decideOrThrow(resolveSubject(), action, ResourceRef.collection(resourceType));
    }

    @Override
    public @NonNull ScopeFilter authorizeCollection(@NonNull SubjectContext subject,
                                                     @NonNull Action action,
                                                     @NonNull String resourceType) {
        return decideOrThrow(subject, action, ResourceRef.collection(resourceType));
    }

    private @NonNull SubjectContext resolveSubject() {
        SubjectContext subject;
        try {
            subject = resolver.resolve();
        } catch (RuntimeException ex) {
            throw new AccessDeniedException("NO_SUBJECT", "无法解析主体上下文（fail-closed）", ex);
        }
        if (subject == null) {
            throw new AccessDeniedException("NO_SUBJECT", "当前请求缺少主体上下文（fail-closed）");
        }
        return subject;
    }

    private @NonNull ScopeFilter decideOrThrow(@NonNull SubjectContext subject,
                                               @NonNull Action action,
                                               @NonNull ResourceRef resource) {
        Decision decision;
        try {
            decision = engine.decide(subject, action, resource);
        } catch (RuntimeException ex) {
            // fail-closed：决策过程异常 → 拒绝，绝不回落放行
            record(subject, action, resource, false, "DECISION_ERROR");
            throw new AccessDeniedException("DECISION_ERROR", "鉴权决策异常", ex);
        }

        if (decision instanceof Decision.Deny deny) {
            record(subject, action, resource, false, deny.reasonCode());
            throw new AccessDeniedException(deny.reasonCode(), deny.message());
        }
        record(subject, action, resource, true, null);
        return ((Decision.Permit) decision).filter();
    }

    /**
     * 写决策日志 —— <b>故障隔离在这里</b>：任何 {@code RuntimeException} 都只记日志、不外抛。
     *
     * <p>把隔离放在门禁而不是只放在 {@code JdbcAuthzDecisionLog} 内部，是因为
     * 隔离必须对<b>所有</b> {@link AuthzDecisionRecorder} 实现成立；否则将来换一个实现
     * （Kafka 上报、HTTP 审计服务）就把同一个坑重新挖出来，而调用方看不出来。
     */
    private void record(@NonNull SubjectContext subject,
                        @NonNull Action action,
                        @NonNull ResourceRef resource,
                        boolean permitted,
                        @Nullable String reasonCode) {
        try {
            recorder.record(subject, action, resource, permitted, reasonCode);
        } catch (RuntimeException ex) {
            LOG.warn("授权决策日志写入失败（已忽略，不影响本次判定结果）: tenant={} action={} resource={} permitted={}",
                    subject.tenantId(), action, resource.type(), permitted, ex);
        }
    }
}
